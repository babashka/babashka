(ns babashka.uberscript-test
  (:require
   [babashka.test-utils :as tu]
   [clojure.test :as t :refer [deftest is]]))

(deftest basic-test
  (let [tmp-file (java.io.File/createTempFile "uberscript" ".clj")]
    (.deleteOnExit tmp-file)
    (is (empty? (tu/bb nil "--classpath" "test-resources/babashka/src_for_classpath_test" "uberscript" (.getPath tmp-file) "-m" "my.main")))
    (is (= "(\"1\" \"2\" \"3\" \"4\")\n"
           (tu/bb nil "--file" (.getPath tmp-file) "1" "2" "3" "4")))))

(when-not (= "aarch64" (System/getenv "BABASHKA_ARCH"))
  (deftest advanced-test
    (let [tmp-file (java.io.File/createTempFile "uberscript" ".clj")]
      (.deleteOnExit tmp-file)
      ;; we test:
      ;; order of namespaces
      ;; reader error for ::a/foo is swallowed
      ;; pod namespaces can be loaded without a problem
      ;; resulting program can be executed
      (is (empty? (tu/bb nil "--classpath" "test-resources/babashka/uberscript/src" "uberscript" (.getPath tmp-file) "-m" "my.main")))
      (is (= ":clojure.string/foo\ntrue\n(\"1\" \"2\" \"3\" \"4\")\n"
             (tu/bb nil "--file" (.getPath tmp-file) "1" "2" "3" "4"))))))

