(ns babashka.file-var-test
  (:require
   [babashka.test-utils :as tu]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is testing]]))

(defn bb [input & args]
  (apply tu/bb (when (some? input) (str input)) (map str args)))

(deftest file-var-test
  (let [[f1 f2 f3 f4]
        (str/split (bb nil "--prn" "--classpath" "test/babashka/scripts"
                       "test/babashka/scripts/file_var.bb")
                   #"\n")]
    (is (str/ends-with? f1 "file_var_classpath.bb"))
    (is (str/ends-with? f2 "loaded_by_file_var.bb"))
    (is (str/ends-with? f3 "file_var.bb"))
    (is (str/ends-with? f4 "file_var.bb")))
  (testing "file var uses absolute path"
    (is (str/includes?
         (bb nil "--prn" (io/file "test" ".." "test"
                                  "babashka" "scripts" "simple_file_var.bb"))
         ".."))))
