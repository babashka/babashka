;; rip-off from https://github.com/clojure/clojure/blob/master/src/clj/clojure/repl/deps.clj
;; but re-implemented using babashka.deps
(ns clojure.repl.deps
  (:require [babashka.deps :as deps]
            [borkdude.deps :as deps.clj]
            [clojure.walk :as walk]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [babashka.process :as p]))

(defn add-libs
  "Given lib-coords, a map of lib to coord, will resolve all transitive deps for the libs
  together and add them to the repl classpath, unlike separate calls to add-lib."
  {:added "1.12"}
  [lib-coords]
  (deps/add-deps {:deps lib-coords})
  nil)

(defn tool-expr [lib]
  (walk/postwalk-replace
   {'lib lib}
   '(let [current-basis (requiring-resolve 'clojure.java.basis/current-basis)
          procurer (select-keys (current-basis) [:mvn/repos :mvn/local-repo])
          invoke-tool (requiring-resolve 'clojure.tools.deps.interop/invoke-tool)
          coord (invoke-tool {:tool-alias :deps
                              :fn 'clojure.tools.deps/find-latest-version
                              :args {:lib 'lib, :procurer procurer}})]
      coord)))

(defn- invoke-tool
  {:added "1.12"}
  [{:keys [tool-name tool-alias fn args preserve-envelope]
    :as opts
    :or {preserve-envelope false}}]
  (when-not (or tool-name tool-alias) (throw (ex-info "Either :tool-alias or :tool-name must be provided" (or opts {}))))
  (when-not (symbol? fn) (throw (ex-info (str "fn should be a symbol " fn) (or opts {}))))
  (let [args (assoc args :clojure.exec/invoke :fn)
        ;; this is a hack since in lein there is no basis and we just make this up:
        args (assoc args :procurer {:mvn/repos {"central" {:url "https://repo1.maven.org/maven2/"}, "clojars" {:url "https://repo.clojars.org/"}}
                                    :mvn/local-repo (str (io/file (System/getProperty "user.home") ".m2"))})
        _ (when (:debug opts) (println "args" args))
        command-strs [(str "-T" (or tool-alias tool-name)) (pr-str fn) (pr-str args)]
        _ (when (:debug opts) (apply println "Invoking: " command-strs))
        envelope (edn/read-string
                  (binding [deps.clj/*clojure-process-fn* (clojure.core/fn [{:keys [cmd]}]
                                                            (let [{:keys [exit out]} (apply p/sh cmd)]
                                                              (if (zero? exit)
                                                                out
                                                                (throw (RuntimeException. (str "Process failed with exit=" exit))))))]
                    (apply deps.clj/-main command-strs)))]
    (if preserve-envelope
      envelope
      (let [{:keys [tag val]} envelope
            parsed-val (edn/read-string val)]
        (if (= :ret tag)
          parsed-val
          (throw (ex-info (:cause parsed-val) (or parsed-val {}))))))))

(defn add-lib
  "Given a lib that is not yet on the repl classpath, make it available by
  downloading the library if necessary and adding it to the classloader.
  Libs already on the classpath are not updated. Requires a valid parent
  DynamicClassLoader.

   lib - symbol identifying a library, for Maven: groupId/artifactId
   coord - optional map of location information specific to the procurer,
           or latest if not supplied

  Returns coll of libs loaded, including transitive (or nil if none).

  For info on libs, coords, and versions, see:
   https://clojure.org/reference/deps_and_cli"
  {:added "1.12"}
  ([lib coord]
   (add-libs {lib coord}))
  ([lib]
   (let [coord (invoke-tool {:tool-alias :deps
                                  :fn 'clojure.tools.deps/find-latest-version
                             :args {:lib lib}})]
     (if coord
       (add-libs {lib coord})
       (throw (ex-info (str "No version found for lib " lib) {}))))))

