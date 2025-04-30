(ns babashka.impl.print-deps
  (:require
   [babashka.deps :as deps]
   [babashka.impl.common :as common]
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
                    'babashka/fs {:mvn/version "0.5.25"}
                    'babashka/babashka.curl {:mvn/version "0.1.2"}
                    'babashka/babashka.core {:git/url "https://github.com/babashka/babashka.core"
                                             :git/sha "52a6037bd4b632bffffb04394fb4efd0cdab6b1e"}
                    'babashka/process {:mvn/version "0.6.23"})
        deps (dissoc deps
                     'borkdude/sci
                     'org.babashka/sci
                     'borkdude/graal.locking
                     'org.postgresql/postgresql
                     'babashka/clojure-lanterna
                     'seancorfield/next.jdbc
                     'datascript/datascript
                     'org.hsqldb/hsqldb)
        bb-edn-deps (:deps @common/bb-edn)
        deps (merge deps bb-edn-deps)
        deps (into (sorted-map) deps)
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
