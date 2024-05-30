(ns babashka.impl.clojure.main-test
  (:require [babashka.test-utils :as tu]
            [clojure.edn :as edn]
            [clojure.test :as t :refer [deftest is testing]]))

(def bb
  (comp edn/read-string tu/bb))

(deftest with-read-known-test
  (testing ":unknown gets set to true"
    (is (true? (bb nil (pr-str '(binding [*read-eval* :unknown]
                                  (clojure.main/with-read-known *read-eval*)))))))
  (testing "other values don't change"
    (t/are [read-eval-value]
      (= read-eval-value
        (bb nil (str "(binding [*read-eval* " read-eval-value "]"
                  " (clojure.main/with-read-known *read-eval*))")))
      false true 5)))
