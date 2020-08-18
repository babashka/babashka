(ns babashka.uberjar-test
  (:require
   [babashka.test-utils :as tu]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is testing]]))

(defn bb [input & args]
  (edn/read-string (apply tu/bb (when (some? input) (str input)) (map str args))))

(deftest uberjar-test
  (let [tmp-file (java.io.File/createTempFile "uber" ".jar")]
    #_(.deleteOnExit tmp-file)
    (testing "uberjar"
      (tu/bb nil "--classpath" "test-resources/babashka/uberjar/src" "-m" "my.main-main" "--uberjar" (.getPath tmp-file))
      (is (= "(\"1\" \"2\" \"3\" \"4\")\n"
             (tu/bb nil "--jar" (.getPath tmp-file) "1" "2" "3" "4")))
      (is (= "(\"1\" \"2\" \"3\" \"4\")\n"
             (tu/bb nil "-jar" (.getPath tmp-file) "1" "2" "3" "4")))
      (is (= "(\"1\" \"2\" \"3\" \"4\")\n"
             (tu/bb nil (.getPath tmp-file) "1" "2" "3" "4"))))
    (testing "without main, a REPL starts"
      (tu/bb nil "--classpath" "test-resources/babashka/uberjar/src" "--uberjar" (.getPath tmp-file))
      ;; TODO: why doesn't this work in the JVM tests?
      #_(is (str/includes? (tu/bb "(+ 1 2 3)\n" "--jar" (.getPath tmp-file)) "6")))))
