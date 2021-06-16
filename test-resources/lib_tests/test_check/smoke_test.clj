(ns test-check.smoke-test)

(require '[clojure.test :as t]
         '[clojure.test.check :as tc]
         '[clojure.test.check.generators :as gen]
         '[clojure.test.check.properties :as prop])

(def property
  (prop/for-all [v (gen/vector gen/small-integer)]
                (let [s (sort v)]
                  (and (= (count v) (count s))
                       (or (empty? s)
                           (apply <= s))))))

;; test our property
(t/deftest smoke-test
  (t/is (= {:result true, :pass? true, :num-tests 100}
           (select-keys (tc/quick-check 100 property)
                        [:result :pass? :num-tests]))))
