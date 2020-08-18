(ns babashka.uberjar-test
  (:require
   [babashka.test-utils :as tu]
   [clojure.edn :as edn]
   [clojure.test :as t :refer [deftest is]]))

(defn bb [input & args]
  (edn/read-string (apply tu/bb (when (some? input) (str input)) (map str args))))

(deftest ubejar-test
  (let [tmp-file (java.io.File/createTempFile "uber" ".jar")]
    (.deleteOnExit tmp-file)
    (tu/bb nil "--classpath" "test-resources/babashka/uberjar/src" "-m" "my.main-main" "--uberjar" (.getPath tmp-file))
    (is (= "(\"1\" \"2\" \"3\" \"4\")\n"
           (tu/bb nil "--jar" (.getPath tmp-file) "1" "2" "3" "4")))
    (is (= "(\"1\" \"2\" \"3\" \"4\")\n"
           (tu/bb nil "-jar" (.getPath tmp-file) "1" "2" "3" "4")))
    (is (= "(\"1\" \"2\" \"3\" \"4\")\n"
           (tu/bb nil (.getPath tmp-file) "1" "2" "3" "4")))))
