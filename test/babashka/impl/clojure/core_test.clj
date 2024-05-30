(ns babashka.impl.clojure.core-test
  (:require [babashka.test-utils :as tu]
            [clojure.test :as t :refer [deftest is testing]]
            [clojure.tools.reader.edn :as edn]))

(def bb
  (comp edn/read-string tu/bb))

(deftest source-path-test
  (testing "source path is nil by default"
    (is (empty? (bb nil "*source-path*"))))
  (testing "source path can be bound dynamically"
    (is (= ["some_value.clj" nil]
          (bb nil (pr-str '(let [x (binding [*source-path* "some_value.clj"]
                                     *source-path*)
                                 y *source-path*]
                             [x y])))))))