(ns babashka.impl.tools-deps
  {:no-doc true}
  (:require [babashka.fs :as fs]
            [babashka.impl.common :as common]
            [clojure.string :as str]
            [sci.core :as sci]))

;; tools.deps runs interpreted, from the sources under resources/src/babashka,
;; with babashka.mvn as its Maven procurer. Nothing in this namespace
;; requires either, so none of it is compiled in. This is the compiled glue:
;; the specs stub and make-classpath2 in-process. The sources' own deviations
;; from upstream are marked BB-PATCH and BB-STAND-IN in the tree.

;; The real clojure.tools.deps.specs is built on clojure.spec, which bb leaves
;; out. clojure.tools.deps.edn calls only these two.
(defn- valid-deps? [_deps-edn] true)
(defn- explain-deps [_deps-edn] nil)

(def sns (sci/create-ns 'clojure.tools.deps.specs nil))

(def specs-namespace
  {'valid-deps? (sci/copy-var valid-deps? sns)
   'explain-deps (sci/copy-var explain-deps sns)})

(def ^:private make-classpath-ns 'clojure.tools.deps.script.make-classpath2)

(defn- prepare! [ctx]
  (sci/eval-form ctx (list 'require (list 'quote make-classpath-ns)
                           ''babashka.mvn.env)))

(def ^:private gitlibs-dir-set (atom nil))

(defn- gitlibs-dir!
  "Points gitlibs at dir, or back at its default when dir is nil, and
  refreshes the config gitlibs read once into a delay."
  [ctx dir]
  (when (not= dir @gitlibs-dir-set)
    (if dir
      (System/setProperty "clojure.gitlibs.dir" dir)
      (System/clearProperty "clojure.gitlibs.dir"))
    (reset! gitlibs-dir-set dir)
    (sci/eval-string*
     ctx
     "(when (find-ns 'clojure.tools.gitlibs.config)
        (alter-var-root (ns-resolve 'clojure.tools.gitlibs.config 'CONFIG)
                        (constantly (delay ((deref (ns-resolve 'clojure.tools.gitlibs.config 'init-config)))))))")))

(defn- override-form
  "A form that runs body with each var, named by a qualified symbol, set
  to its value for the run and restored after."
  [[[var-sym value] & more] body]
  (if var-sym
    (list 'let ['v (list 'var var-sym) 'orig (list 'deref 'v)]
          (list 'alter-var-root 'v (list 'constantly value))
          (list 'try (override-form more body)
                (list 'finally (list 'alter-var-root 'v (list 'constantly 'orig)))))
    body))

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

(def ^:private run-lock (Object.))

(defn make-classpath!
  "Runs clojure.tools.deps.script.make-classpath2 in this process with the
  arguments deps.clj passes to it. dir is the project directory. opts
  carry the call's environment: :getenv, its lookup function, and
  :config-dir, the user config dir deps.clj found in it, which tools.deps
  would otherwise take from the process environment."
  [dir args {:keys [config-dir getenv]}]
  (let [ctx (common/ctx)
        ;; deps.clj passes nil for --config-user under -Srepro, the CLI
        ;; script passes the empty string
        args (mapv #(if (nil? %) "" (str %)) args)
        dir (fs/absolutize (fs/file (or dir (System/getProperty "user.dir"))))]
    (prepare! ctx)
    (let [{:keys [options errors]}
          (sci/eval-form ctx (list (symbol (str make-classpath-ns) "parse-opts")
                                   (list 'quote args)))]
      (when (seq errors)
        (throw (ex-info (str/join "\n" errors) {:args args})))
      (let [options (absolutize-files dir options)
            run (list 'clojure.tools.deps.util.dir/with-dir
                      (list 'clojure.java.io/file (str dir))
                      (list (symbol (str make-classpath-ns) "run")
                            (list 'quote options)))]
        ;; the gitlibs dir, user-config-dir and the environment are
        ;; process-wide state, so calls run one at a time
        (locking run-lock
          (gitlibs-dir! ctx (getenv "GITLIBS"))
          (sci/eval-form ctx
                         (override-form
                          (cond-> [['babashka.mvn.env/getenv getenv]]
                            config-dir (conj ['clojure.tools.deps.edn/user-config-dir
                                              (constantly config-dir)]))
                          run)))))))
