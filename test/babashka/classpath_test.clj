(ns babashka.classpath-test
  (:require
   [babashka.test-utils :as tu]
   [clojure.edn :as edn]
   [clojure.test :as t :refer [deftest is]]))

(defn bb [input & args]
  (edn/read-string (apply tu/bb (when (some? input) (str input)) (map str args))))

(deftest classpath-test
  (is (= :my-script/bb
         (bb nil "--classpath" "test-resources/babashka/src_for_classpath_test"
             "(require '[my-script :as ms]) (ms/foo)")))
  (is (= "hello from foo\n"
         (tu/bb nil "--classpath" "test-resources/babashka/src_for_classpath_test/foo.jar"
                "(require '[foo :as f]) (f/foo)"))))

(deftest main-test
  (is (= "hello from foo\n"
         (tu/bb nil "--classpath" "test-resources/babashka/src_for_classpath_test" "-m" "my.main" "1" "2" "3" "4"))))
