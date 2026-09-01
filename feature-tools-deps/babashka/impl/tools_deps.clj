(ns babashka.impl.tools-deps
  {:no-doc true}
  (:require [babashka.fs :as fs]
            [babashka.impl.common :as common]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [sci.core :as sci])
  (:import [eu.maveniverse.maven.mima.runtime.standalonestatic StandaloneStaticRuntime]
           [org.eclipse.aether.transfer TransferListener]))

;; clojure.tools.deps.util.maven reifies TransferListener at load time. bb's
;; reify registry does not carry it, so this adapter serves that one case.
(defn- transfer-listener [{:keys [methods]}]
  (let [call (fn [name this ev]
               (when-let [f (get methods name)]
                 (f this ev)))]
    (reify TransferListener
      (transferInitiated [this ev] (call 'transferInitiated this ev))
      (transferStarted [this ev] (call 'transferStarted this ev))
      (transferProgressed [this ev] (call 'transferProgressed this ev))
      (transferCorrupted [this ev] (call 'transferCorrupted this ev))
      (transferSucceeded [this ev] (call 'transferSucceeded this ev))
      (transferFailed [this ev] (call 'transferFailed this ev)))))

(defn reify-fn
  "Fallback for bb's reify-fn. Returns nil for anything it does not handle."
  [{:keys [interfaces] :as m}]
  (when (= #{TransferListener} interfaces)
    (transfer-listener m)))

;; tools.deps runs interpreted, from the sources under resources/src/babashka.
;; Nothing in this namespace requires it, so none of it is compiled in.

;; The real clojure.tools.deps.specs is built on clojure.spec, which bb leaves
;; out. clojure.tools.deps.edn calls only these two.
(defn- valid-deps? [_deps-edn] true)
(defn- explain-deps [_deps-edn] nil)

(def sns (sci/create-ns 'clojure.tools.deps.specs nil))

(def specs-namespace
  {'valid-deps? (sci/copy-var valid-deps? sns)
   'explain-deps (sci/copy-var explain-deps sns)})

;; Read at build time from the tools.deps.edn jar.
(def ^:private root-deps-edn
  (edn/read-string (slurp (io/resource "clojure/tools/deps/deps.edn"))))

(def ^:private make-classpath-ns 'clojure.tools.deps.script.make-classpath2)

(defn- sci-var [ctx ns sym]
  (sci/eval-form ctx (list 'ns-resolve (list 'quote ns) (list 'quote sym))))

(defn- prepare! [ctx]
  (sci/eval-form ctx (list 'require (list 'quote make-classpath-ns)))
  ;; MIMA picks its runtime with a ServiceLoader, which native-image does not
  ;; register. Only one provider ships.
  (sci/alter-var-root (sci-var ctx 'clojure.tools.deps.util.maven 'the-runtime)
                      (constantly (delay (StandaloneStaticRuntime.))))
  ;; The root deps.edn is a jar resource, invisible to bb's classpath.
  (sci/alter-var-root (sci-var ctx 'clojure.tools.deps.edn 'root-deps)
                      (constantly (fn [] root-deps-edn))))

(def ^:private file-opts
  [:config-user :config-project :cp-file :jvm-file :main-file :manifest-file :basis-file])

(defn- absolutize-files [dir opts]
  (reduce (fn [opts k]
            (if-let [p (get opts k)]
              (assoc opts k (str (fs/path dir p)))
              opts))
          opts
          file-opts))

(defn make-classpath!
  "Runs clojure.tools.deps.script.make-classpath2 in this process with the
  arguments deps.clj passes to it. dir is the project directory."
  [dir args]
  (let [ctx (common/ctx)
        args (mapv str args)
        dir (fs/file (or dir (System/getProperty "user.dir")))]
    (prepare! ctx)
    (let [{:keys [options errors]}
          (sci/eval-form ctx (list (symbol (str make-classpath-ns) "parse-opts")
                                   (list 'quote args)))]
      (when (seq errors)
        (throw (ex-info (str/join "\n" errors) {:args args})))
      (sci/eval-form ctx (list 'clojure.tools.deps.util.dir/with-dir
                               (list 'clojure.java.io/file (str dir))
                               (list (symbol (str make-classpath-ns) "run")
                                     (list 'quote (absolutize-files dir options))))))))
