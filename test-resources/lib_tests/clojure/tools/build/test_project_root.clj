(ns clojure.tools.build.test-project-root
  (:require
   [clojure.java.io :as jio]
   [clojure.test :refer :all]
   [clojure.tools.build.api :as api])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

(deftest test-with-project-root
  (let [path (Files/createTempDirectory "p" (make-array FileAttribute 0))
        root-dir (jio/file (.toString path))
        p-dir (jio/file root-dir "p")
        deps (jio/file p-dir "deps.edn")]
    (jio/make-parents deps)
    (spit deps "{:deps {org.clojure/data.json {:mvn/version \"2.3.0\"}}}")
    (api/set-project-root! (.toString path))
    (api/with-project-root (.getPath (jio/file (.toString path) "p"))
      (is (contains? (-> (api/create-basis) :libs keys set) 'org.clojure/data.json)))))

(comment
  (test-with-project-root)
  )