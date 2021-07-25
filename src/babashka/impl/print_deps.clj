(ns babashka.impl.print-deps
  (:require
   [babashka.impl.common :as common]
   [babashka.impl.deps :as deps]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pp]))

(defn print-deps [deps-format]
  (let [deps (-> (io/resource "META-INF/babashka/deps.edn")
                 slurp
                 edn/read-string)
        deps (update deps :deps assoc
                     'babashka/fs {:mvn/version "0.0.5"}
                     'babashka/babashka.curl {:mvn/version "0.0.3"})
        deps (update deps :deps dissoc
                     'borkdude/sci
                     'borkdude/graal.locking
                     'org.postgresql/postgresql
                     'babashka/clojure-lanterna
                     'seancorfield/next.jdbc
                     'datascript/datascript)
        bb-edn-deps (:deps @common/bb-edn)
        deps (merge deps bb-edn-deps)
        deps {:deps (:deps deps)}]
    (case deps-format
      ("deps" nil) (binding [*print-namespace-maps* false]
                     (pp/pprint deps))
      ("classpath") (println (with-out-str (deps/clojure ["-Spath" "-Sdeps" deps]))))))
