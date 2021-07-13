(ns babashka.pod-test
  (:require [babashka.main :as main]
            [babashka.test-utils :as tu]
            [clojure.edn :as edn]
            [clojure.test :as t :refer [deftest is]]))

(deftest pod-test
  (if (= "true" (System/getenv "BABASHKA_POD_TEST"))
    (let [native? tu/native?
          windows? main/windows?
          sw (java.io.StringWriter.)
          res (apply tu/bb {:err sw}
                     (cond-> ["-f" "test-resources/pod.clj"]
                       native?
                       (conj "--native")
                       windows?
                       (conj "--windows")))
          err (str sw)]
      (is (= "6\n1\n2\n3\n4\n5\n6\n7\n8\n9\n\"Illegal arguments / {:args (1 2 3)}\"\n(\"hello\" \"print\" \"this\" \"debugging\" \"message\")\ntrue\n" res))
      (when-not tu/native?
        (is (= "(\"hello\" \"print\" \"this\" \"error\")\n" (tu/normalize err))))
      (is (= {:a 1 :b 2}
             (edn/read-string
              (apply tu/bb nil (cond-> ["-f" "test-resources/pod.clj" "--json"]
                                 native?
                                 (conj "--native")
                                 windows?
                                 (conj "--windows")))))))
    (println "Skipping pod test because BABASHKA_POD_TEST isn't set to true.")))
