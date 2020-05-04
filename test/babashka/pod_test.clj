(ns babashka.pod-test
  (:require [babashka.test-utils :as tu]
            [clojure.test :as t :refer [deftest is]]))

(deftest pod-test
  (let [native? tu/native?]
    (is (= "6\n1\n2\n3\n4\n5\n6\n7\n8\n9\n"
           (apply tu/bb nil (cond-> ["-f" "test-resources/pod.clj"]
                              native?
                              (conj "--native")))))))
