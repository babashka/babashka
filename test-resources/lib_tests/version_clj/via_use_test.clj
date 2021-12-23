(ns version-clj.via-use-test
  "Babashka was failing when loading version-clj via `use` and `require`->`:refer`.
  The unit tests transcribed from version-clj address the require->refer case.
  This set of tests spot-check that loading via use works for version-clj."
  (:require [clojure.test :refer [deftest is]]))

(use 'version-clj.core)

;; BB-TEST-PATCH: This test doesn't exist upstream
(deftest sanity-test
  (is (= [[1 0 0] ["snapshot"]] (version->seq "1.0.0-SNAPSHOT")))
  (is (= 0 (version-compare "1.0" "1.0.0")))
  (is (= -1 (version-compare "1.0-alpha5" "1.0-alpha14")))
  (is (= 1 (version-compare "1.0-alpha14" "1.0-alpha5"))) )
