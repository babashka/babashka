(ns babashka.agent-test
  (:require
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.test :as t :refer [deftest is]]))

(deftest agent-binding-conveyance-test
  (let [prog
        "(def ^:dynamic *foo* 1) (def a (agent nil)) (binding [*foo* 2] (send-off a (fn [_] *foo*))) (await a) (shutdown-agents) @a"]
    (is (= 2 (edn/read-string (test-utils/bb nil prog))))))
