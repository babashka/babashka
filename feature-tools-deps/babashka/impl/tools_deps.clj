(ns babashka.impl.tools-deps
  {:no-doc true}
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.tools.deps :as deps]
            [clojure.tools.deps.script.make-classpath2 :as mc2]
            [clojure.tools.deps.util.dir :as dir]
            [clojure.tools.deps.util.maven :as mvn]
            [sci.core :as sci])
  (:import [eu.maveniverse.maven.mima.runtime.standalonestatic StandaloneStaticRuntime]))

;; MIMA picks its runtime with a ServiceLoader, which native-image does not
;; register. Only one provider ships, so bind it here, at build time.
(alter-var-root #'mvn/the-runtime (constantly (delay (StandaloneStaticRuntime.))))

(def ^:private file-opts
  [:config-user :config-project :cp-file :jvm-file :main-file :manifest-file :basis-file])

(defn- absolutize-files [dir opts]
  (reduce (fn [opts k]
            (if-let [p (get opts k)]
              (assoc opts k (str (fs/path dir p)))
              opts))
          opts
          file-opts))

;; bb's script classloader hides image resources such as the root deps.edn.
;; tools.deps reads those through the context classloader, so run it with the
;; parent loader, which sees them.
(defn- with-image-loader* [f]
  (let [t (Thread/currentThread)
        loader (.getContextClassLoader t)]
    (try
      (.setContextClassLoader t (.getParent loader))
      (f)
      (finally
        (.setContextClassLoader t loader)))))

(defn make-classpath!
  "Runs clojure.tools.deps.script.make-classpath2 in this process with the
  arguments deps.clj passes to it. dir is the project directory."
  [dir args]
  (let [args (mapv str args)
        {:keys [options errors]} (mc2/parse-opts args)
        dir (fs/file (or dir (System/getProperty "user.dir")))]
    (when (seq errors)
      (throw (ex-info (str/join "\n" errors) {:args args})))
    (with-image-loader*
      (fn []
        (dir/with-dir dir
          (mc2/run (absolutize-files dir options)))))))

(def tns (sci/create-ns 'clojure.tools.deps nil))

(def tools-deps-namespace
  {'calc-basis (sci/copy-var deps/calc-basis tns)
   'create-basis (sci/copy-var deps/create-basis tns)
   'find-latest-version (sci/copy-var deps/find-latest-version tns)
   'join-classpath (sci/copy-var deps/join-classpath tns)
   'lib-location (sci/copy-var deps/lib-location tns)
   'make-classpath-map (sci/copy-var deps/make-classpath-map tns)
   'print-tree (sci/copy-var deps/print-tree tns)
   'resolve-added-libs (sci/copy-var deps/resolve-added-libs tns)
   'resolve-deps (sci/copy-var deps/resolve-deps tns)
   'root-deps (sci/copy-var deps/root-deps tns)
   'user-deps-path (sci/copy-var deps/user-deps-path tns)})
