(ns babashka.impl.server-test
  (:require [babashka.test-utils :as tu]
            [clojure.edn :as edn]
            [clojure.test :as t :refer [deftest is testing]]))

(def bb
  (comp edn/read-string tu/bb))

(deftest repl-read-test
  (testing "arbitrary values can be read"
    (t/are [input result]
      (= result (bb input "(let [request-exit (Object.)]
                           (loop [acc []]
                             (let [v (clojure.core.server/repl-read nil request-exit)]
                               (if (= v request-exit)
                                 acc
                                 (recur (conj acc v))))))"))
      "abc" '[abc]
      "123 456" [123 456]
      "(nil ns/symbol (true))\n  (+ 1 2 3)" '[(nil ns/symbol (true)) (+ 1 2 3)])))
