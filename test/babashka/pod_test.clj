(ns babashka.pod-test
  (:require [babashka.test-utils :as tu]
            [clojure.edn :as edn]
            [clojure.test :as t :refer [deftest is]]))

(deftest pod-test
  (let [native? tu/native?
        sw (java.io.StringWriter.)
        res (apply tu/bb {:err sw}
                   (cond-> ["-f" "test-resources/pod.clj"]
                     native?
                     (conj "--native")))
        err (str sw)]
    (is (= "6\n1\n2\n3\n4\n5\n6\n7\n8\n9\n\"Illegal arguments / {:args (1 2 3)}\"\n(\"hello\" \"print\" \"this\" \"debugging\" \"message\")\ntrue\n" res))
    (when-not tu/native?
      (is (= "(\"hello\" \"print\" \"this\" \"error\")\n" err)))
    (is (= {:a 1 :b 2}
           (edn/read-string
            (apply tu/bb nil (cond-> ["-f" "test-resources/pod.clj" "--json"]
                               native?
                               (conj "--native"))))))))
