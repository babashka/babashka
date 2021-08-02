(ns babashka.impl.print-deps
  (:require
   [babashka.impl.common :as common]
   [babashka.impl.deps :as deps]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [sci.core :as sci]))

(defn print-deps [deps-format]
  (let [deps (-> (io/resource "META-INF/babashka/deps.edn")
                 slurp
                 edn/read-string)
        deps (:deps deps)
        deps (assoc deps
                    'babashka/fs {:mvn/version "0.0.5"}
                    'babashka/babashka.curl {:mvn/version "0.0.3"})
        deps (dissoc deps
                     'borkdude/sci
                     'borkdude/graal.locking
                     'org.postgresql/postgresql
                     'babashka/clojure-lanterna
                     'seancorfield/next.jdbc
                     'datascript/datascript
                     'org.hsqldb/hsqldb)
        bb-edn-deps (:deps @common/bb-edn)
        deps (merge deps bb-edn-deps)
        paths (:paths @common/bb-edn)
        deps {:deps deps}
        deps (cond-> deps
               (seq paths) (assoc :paths paths))]
    (case deps-format
      ("deps" nil) (binding [*print-namespace-maps* false]
                     (pp/pprint deps))
      ("classpath") (let [cp (str/trim (sci/with-out-str
                                         (deps/clojure ["-Spath" "-Sdeps" deps] {:out :string})))]
                      (println cp)))))
