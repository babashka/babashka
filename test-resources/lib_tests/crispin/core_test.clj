(ns crispin.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [crispin.core :as cfg]))

(deftest crispin.core-test
  (testing "config from multiple sources"
    (do
      (cfg/load-custom-cfg! "test-resources/lib_tests/crispin" "crispin-test-custom-cfg.edn")
      (System/setProperty "crispintest.value" "yes")
      (System/setProperty "crispin" "test-resources/lib_tests/crispin/crispin-test-cfg.edn")
      (let [c (cfg/cfg)]
        ; something from the environment
        (is (not-empty (cfg/sget c :path)))
        ; things from the resource named by the :crispin property
        (is (= "pina colada" (cfg/sget-in c [:likes 0])))
        (is (= 3.14 (cfg/nget-in c [:crispintest :pi])))
        ; something from system properties
        (is (true? (cfg/bget-in c [:crispintest :value])))
        ; something from load-custom-cfg! file
        (is (= :bar (:foo c))))
      (System/clearProperty "crispintest.value")
      (System/clearProperty "crispin"))))
