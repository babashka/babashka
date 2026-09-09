(ns clojure.tools.build.tasks.test-basis
  (:require
    [clojure.test :refer :all]
    [clojure.tools.build.api :as api]
    [clojure.tools.build.test-util :refer :all])
  (:import
    [java.nio.file Files]
    [java.nio.file.attribute FileAttribute]))

(deftest test-missing-project-deps-file
  (let [path (Files/createTempDirectory "abc" (make-array FileAttribute 0))]
    (with-test-dir (.toString path)
      (api/set-project-root! (.getAbsolutePath *test-dir*))
      (api/create-basis {}))))

(comment
  (run-tests)
  )


