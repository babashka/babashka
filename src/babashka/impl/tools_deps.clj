(ns babashka.impl.tools-deps
  {:no-doc true}
  (:require [babashka.fs :as fs]
            [babashka.impl.common :as common]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [sci.core :as sci]))

;; tools.deps runs interpreted, from the sources under resources/src/babashka,
;; with babashka.mvn as its Maven procurer. Nothing in this namespace
;; requires either, so none of it is compiled in. This is the compiled glue:
;; the specs stub, the one source patch, and make-classpath2 in-process.

;; The real clojure.tools.deps.specs is built on clojure.spec, which bb leaves
;; out. clojure.tools.deps.edn calls only these two.
(defn- valid-deps? [_deps-edn] true)
(defn- explain-deps [_deps-edn] nil)

(def sns (sci/create-ns 'clojure.tools.deps.specs nil))

(def specs-namespace
  {'valid-deps? (sci/copy-var valid-deps? sns)
   'explain-deps (sci/copy-var explain-deps sns)})

;; Read at build time: the root deps.edn of tools.deps.edn, vendored next to
;; its sources.
(def ^:private root-deps-edn
  (edn/read-string (slurp (io/resource "src/babashka/clojure/tools/deps/deps.edn"))))

(def ^:private make-classpath-ns 'clojure.tools.deps.script.make-classpath2)

;; Appended to the bundled sources when bb's load-fn serves them. The root
;; deps.edn is a jar resource the image cannot see, so it travels as data.
;; The procurer itself is required by the stand-in extensions/maven.clj.
(def ^:private source-patches
  {'clojure.tools.deps.edn
   (binding [*print-namespace-maps* false]
     (str "\n(alter-var-root #'root-deps (constantly (fn [] '" (pr-str root-deps-edn) ")))\n"))})

(defn patch-source
  "Returns source with the patches for namespace appended."
  [namespace source]
  (if-let [patch (get source-patches namespace)]
    (str source patch)
    source))

(defn- prepare! [ctx]
  (sci/eval-form ctx (list 'require (list 'quote make-classpath-ns))))

(def ^:private file-opts
  [:config-user :config-project :cp-file :jvm-file :main-file :manifest-file :basis-file])

(defn- absolutize-files
  "Resolves file options to absolute paths relative to dir."
  [dir opts]
  (reduce (fn [opts k]
            (if-let [p (get opts k)]
              (assoc opts k (str (fs/absolutize (fs/path dir p))))
              opts))
          opts
          file-opts))

(defn make-classpath!
  "Runs clojure.tools.deps.script.make-classpath2 in this process with the
  arguments deps.clj passes to it. dir is the project directory."
  [dir args]
  (let [ctx (common/ctx)
        args (mapv str args)
        dir (fs/absolutize (fs/file (or dir (System/getProperty "user.dir"))))]
    (prepare! ctx)
    (let [{:keys [options errors]}
          (sci/eval-form ctx (list (symbol (str make-classpath-ns) "parse-opts")
                                   (list 'quote args)))]
      (when (seq errors)
        (throw (ex-info (str/join "\n" errors) {:args args})))
      (let [options (absolutize-files dir options)
            ;; deps.clj derived the user config dir from the resolve's own
            ;; environment, CLJ_CONFIG included, and named it in
            ;; --config-user. tools.deps reads the process environment for
            ;; the same thing when it looks up a named tool's descriptor, so
            ;; for this run its answer is deps.clj's.
            config-dir (some-> (:config-user options) fs/parent str)
            run (list 'clojure.tools.deps.util.dir/with-dir
                      (list 'clojure.java.io/file (str dir))
                      (list (symbol (str make-classpath-ns) "run")
                            (list 'quote options)))]
        (sci/eval-form ctx
                       (if config-dir
                         (list 'let ['v (list 'ns-resolve ''clojure.tools.deps.edn ''user-config-dir)
                                     'orig (list 'deref 'v)]
                               (list 'alter-var-root 'v (list 'constantly (list 'constantly config-dir)))
                               (list 'try run
                                     (list 'finally (list 'alter-var-root 'v (list 'constantly 'orig)))))
                         run))))))
