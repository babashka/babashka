;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build.tasks.test-delete
  (:require
    [clojure.test :refer :all :as test]
    [clojure.java.io :as jio]
    [clojure.tools.build.api :as api]
    [clojure.tools.build.test-util :refer :all]))

(deftest test-delete
  (with-test-dir "test-data/p1"
    (api/set-project-root! (.getAbsolutePath *test-dir*))
    ;; copy src into target, then delete, and check target dir is gone
    (api/copy-dir {:target-dir "target/classes"
               :src-dirs ["src"]})
    (api/delete {:path "target"})
    (is (false? (.exists (jio/file (project-path "target/classes")))))))

(comment
  (run-tests)
  )
