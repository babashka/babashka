(ns jasentaa.test-helpers
  (:require [jasentaa.monad :as m]
            [jasentaa.position :as p]))

(defn test-harness [parser input]
  (let [result (first (parser (p/augment-location input)))]
    (if (empty? result)
      (m/failure)
      (list [(if (char? (-> result first :char))
               (-> result first :char)
               (mapv :char (first result)))
             (p/strip-location (fnext result))]))))
