;; Writes models/expected.edn: tools.deps' read-model-file and model-deps on
;; the JVM for each POM under models/, with the repositories of the Maven
;; model as :mvn/repos data. deps_maven_test.clj compares bb against it.
;; Run: clojure -Sdeps '{:deps {org.clojure/tools.deps {:mvn/version "0.31.1646"}}}' -M script/mvn_oracle/models_jvm.clj
(ns models-jvm
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.deps.extensions.pom :as pom])
  (:import [org.apache.maven.model Model Repository RepositoryPolicy]))

(def dir "script/mvn_oracle/models")

(defn- policy [^RepositoryPolicy p]
  (when p
    (let [update (.getUpdatePolicy p) checksum (.getChecksumPolicy p) enabled (.getEnabled p)]
      (not-empty
       (cond-> {}
         enabled (assoc :enabled (Boolean/parseBoolean enabled))
         update (assoc :update (if-let [[_ n] (re-matches #"interval:(\d+)" update)] (parse-long n) (keyword update)))
         checksum (assoc :checksum (keyword checksum)))))))

(defn- repos [^Model model]
  (into (array-map)
        (map (fn [^Repository r]
               [(.getId r) (cond-> {:url (.getUrl r)}
                             (policy (.getReleases r)) (assoc :releases (policy (.getReleases r)))
                             (policy (.getSnapshots r)) (assoc :snapshots (policy (.getSnapshots r))))]))
        (.getRepositories model)))

(def config {:mvn/repos {"central" {:url "https://repo1.maven.org/maven2/"}}})

(defn- model-data [^java.io.File file]
  (try (let [model (pom/read-model-file file config)
             r (repos model)]
         {:deps (vec (pom/model-deps model)) :repos r :repo-order (vec (keys r))})
       (catch Exception _ :throws)))

(let [root (io/file dir)
      files (->> (file-seq root)
                 (filter #(str/ends-with? (.getName ^java.io.File %) ".xml"))
                 (sort-by str))]
  (spit (io/file dir "expected.edn")
        (with-out-str
          (pprint/pprint
           (into (sorted-map)
                 (map (fn [f] [(str (.relativize (.toPath root) (.toPath ^java.io.File f))) (model-data f)]))
                 files)))))
