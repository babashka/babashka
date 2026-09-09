;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build.tasks.test-javac
  (:require
    [clojure.string :as str]
    [clojure.test :refer :all :as test]
    [clojure.java.io :as jio]
    [clojure.tools.build.api :as api]
    [clojure.tools.build.test-util :refer :all]))

(deftest test-javac
  (with-test-dir "test-data/p1"
    (api/set-project-root! (.getAbsolutePath *test-dir*))
    (api/javac {:class-dir "target/classes"
                :src-dirs ["java"]})
    (is (true? (.exists (jio/file (project-path "target/classes/foo/Demo1.class")))))
    (is (true? (.exists (jio/file (project-path "target/classes/foo/Demo2.class")))))
    (let [class-path (.getPath (jio/file (project-path "target/classes")))]
      (is (= "Hello" (str/trim (:out (api/process {:command-args ["java" "-cp" class-path "foo.Demo1"] :out :capture})))))
      (is (= "Hello" (str/trim (:out (api/process {:command-args ["java" "-cp" class-path "foo.Demo2"] :out :capture}))))))))

(comment
  (run-tests)
  )
