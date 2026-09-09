;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build.tasks.test-write-file
  (:require
    [clojure.test :refer :all :as test]
    [clojure.edn :as edn]
    [clojure.java.io :as jio]
    [clojure.tools.build.api :as api]
    [clojure.tools.build.test-util :refer :all]))

(deftest test-touch
  (with-test-dir "test-data/p1"
    (api/set-project-root! (.getAbsolutePath *test-dir*))
    (api/write-file {:path "target/out.txt"})
    (is (.exists (jio/file (project-path "target/out.txt"))))))

(deftest test-write-data
  (with-test-dir "test-data/p1"
    (let [data {:abc "abc" :def [1 2 3]}]
      (api/set-project-root! (.getAbsolutePath *test-dir*))
      (api/write-file {:path "target/out.txt"
                       :content data})
      (let [out-file (jio/file (project-path "target/out.txt"))]
        (is (.exists out-file))
        (is (= data (edn/read-string (slurp out-file))))))))

(deftest test-write-string
  (with-test-dir "test-data/p1"
    (let [string "abcd/nef/n"]
      (api/set-project-root! (.getAbsolutePath *test-dir*))
      (api/write-file {:path "target/out.txt"
                       :string string})
      (let [out-file (jio/file (project-path "target/out.txt"))]
        (is (.exists out-file))
        (is (= string (slurp out-file)))))))

(comment
  (run-tests)
  )
