;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build.tasks.test-install
  (:require
    [clojure.java.io :as jio]
    [clojure.test :as test :refer [deftest is]]
    [clojure.tools.build.api :as api]
    [clojure.tools.build.test-util :refer [with-test-dir *test-dir* project-path]]))

(def test-org (str (gensym "ORG")))
(def test-lib (str (gensym "LIB")))
(def lib (symbol test-org test-lib))
(def version "1.0.0")

(deftest test-install-no-pom
  (with-test-dir "test-data/p1"
    (let [classes "target/classes"
          jar-path "target/output.jar"
          local-repo (project-path "tmp-repo")
          basis (api/create-basis {:project "deps.edn", :extra {:mvn/local-repo local-repo}})]
      (api/set-project-root! (.getAbsolutePath *test-dir*))
      (api/copy-dir {:src-dirs ["src"], :target-dir classes})
      (api/write-pom {:basis basis, :class-dir classes, :lib lib, :version version})
      (api/jar {:class-dir classes, :jar-file jar-path})
      (api/install {:basis basis
                    :jar-file jar-path
                    :lib lib
                    :class-dir classes
                    :version version})
      (let [expected-dir (jio/file local-repo test-org)
            expected-jar (jio/file expected-dir test-lib version (str test-lib "-1.0.0.jar"))
            expected-pom (jio/file expected-dir test-lib version (str test-lib "-1.0.0.pom"))]
        (is (.exists expected-dir))
        (is (.exists expected-jar))
        (is (.exists expected-pom))))))

(comment
  (test/run-tests)
  )
