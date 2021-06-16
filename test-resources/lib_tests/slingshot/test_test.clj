(ns slingshot.test-test
  (:require [clojure.test :refer :all]
            [slingshot.slingshot :refer [throw+]]
            [slingshot.test]))

(deftest test-slingshot-test-macros
  (is (thrown+? string? (throw+ "test")))
  (is (thrown+-with-msg? string? #"th" (throw+ "test" "hi there"))))
