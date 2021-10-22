(ns babashka.uberscript-test
  (:require
   [babashka.test-utils :as tu]
   [clojure.test :as t :refer [deftest is testing]]))

(deftest uberscript-test
  (let [tmp-file (java.io.File/createTempFile "uberscript" ".clj")]
    (.deleteOnExit tmp-file)
    (is (empty? (tu/bb nil "--classpath" "test-resources/babashka/src_for_classpath_test" "-m" "my.main" "--uberscript" (.getPath tmp-file))))
    (is (= "(\"1\" \"2\" \"3\" \"4\")\n"
           (tu/bb nil "--file" (.getPath tmp-file) "1" "2" "3" "4")))))

(deftest order-test
  (let [tmp-file (java.io.File/createTempFile "uberscript" ".clj")]
    (.deleteOnExit tmp-file)
    (testing "order of namespaces is correct"
      (is (empty? (tu/bb nil "--classpath" "test-resources/babashka/uberscript/src" "-m" "my.main" "--uberscript" (.getPath tmp-file))))
      (is (= ":clojure.string/foo\n(\"1\" \"2\" \"3\" \"4\")\n"
             (tu/bb nil "--file" (.getPath tmp-file) "1" "2" "3" "4"))))))

