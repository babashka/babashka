(ns babashka.impl.tasks-test
  (:require [babashka.impl.tasks :as sut]
            [clojure.test :as t]))

(t/deftest target-order-test
  (t/is (= '[quux bar foo]
           (sut/target-order
            {'foo {:depends ['bar 'quux]}
             'bar {:depends ['quux]}}
            'foo))))
