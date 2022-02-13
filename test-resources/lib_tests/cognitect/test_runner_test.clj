(ns cognitect.test-runner-test
  (:require
    [clojure.test :refer :all]
    [cognitect.test-runner :as tr]))

(deftest ns-filters
  (are [ns-names ns-regexes available selected]
    (= selected (filter (#'tr/ns-filter {:namespace ns-names :namespace-regex ns-regexes}) available))

    ;; default settings (no -n / -r, use default for -r)
    nil nil nil []
    nil nil '[ns1-test ns2-test] '[ns1-test ns2-test]
    nil nil '[ns1-test ns2-test ns3 ns4 ns5] '[ns1-test ns2-test]

    ;; specific namespaces
    '#{ns3} nil '[ns1-test ns2-test] '[]
    '#{ns3 ns4} nil '[ns1-test ns2-test ns3 ns4 ns5] '[ns3 ns4]

    ;; regexes
    nil #{#"ns1.*" #"ns3"} '[ns1-test ns2-test ns3 ns4] '[ns1-test ns3]

    ;; both
    '#{ns3} '#{#"ns1.*"} '[ns1-test ns2-test ns3 ns4] '[ns1-test ns3]))