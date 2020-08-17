(ns babashka.uberscript-test
  (:require
   [babashka.test-utils :as tu]
   [clojure.edn :as edn]
   [clojure.test :as t :refer [deftest is testing]]))

(defn bb [input & args]
  (edn/read-string (apply tu/bb (when (some? input) (str input)) (map str args))))

(deftest uberscript-test
  (let [tmp-file (java.io.File/createTempFile "uberscript" ".clj")]
    (.deleteOnExit tmp-file)
    (is (empty? (tu/bb nil "--classpath" "test-resources/babashka/src_for_classpath_test" "-m" "my.main" "--uberscript" (.getPath tmp-file))))
    (is (= "(\"1\" \"2\" \"3\" \"4\")\n"
           (tu/bb nil "--file" (.getPath tmp-file) "1" "2" "3" "4")))
    (testing "order of namespaces is correct"
      (tu/bb nil "--classpath" "test-resources/babashka/uberscript/src" "-m" "my.main" "--uberscript" (.getPath tmp-file))
      (spit "/tmp/foo.clj" (slurp (.getPath tmp-file)))
      (is (= "(\"1\" \"2\" \"3\" \"4\")\n"
             (tu/bb nil "--file" (.getPath tmp-file) "1" "2" "3" "4"))))))

