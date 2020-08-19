(ns babashka.uberjar-test
  (:require
   [babashka.test-utils :as tu]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is testing]]))

(defn bb [input & args]
  (edn/read-string (apply tu/bb (when (some? input) (str input)) (map str args))))

(deftest uberjar-test
  (let [tmp-file (java.io.File/createTempFile "uber" ".jar")
        path (.getPath tmp-file)]
    (.deleteOnExit tmp-file)
    (testing "uberjar"
      (tu/bb nil "--classpath" "test-resources/babashka/uberjar/src" "-m" "my.main-main" "--uberjar" path)
      (is (= "(\"1\" \"2\" \"3\" \"4\")\n"
             (tu/bb nil "--jar" path "1" "2" "3" "4")))
      (is (= "(\"1\" \"2\" \"3\" \"4\")\n"
             (tu/bb nil "-jar" path "1" "2" "3" "4")))
      (is (= "(\"1\" \"2\" \"3\" \"4\")\n"
             (tu/bb nil path "1" "2" "3" "4")))))
  (testing "without main, a REPL starts"
    ;; NOTE: if we choose the same tmp-file as above and doing this all in the
    ;; same JVM process, the below test fails because my.main-main will be the
    ;; main class in the manifest, even if we delete the tmp-file, which may
    ;; indicate a state-related bug somewhere!
    (let [tmp-file (java.io.File/createTempFile "uber" ".jar")
          path (.getPath tmp-file)]
      (.deleteOnExit tmp-file)
      (tu/bb nil  "--classpath" "test-resources/babashka/uberjar/src" "--uberjar" path)
      (is (str/includes? (tu/bb "(+ 1 2 3)" path) "6")))))
