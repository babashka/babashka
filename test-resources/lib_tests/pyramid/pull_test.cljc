(ns pyramid.pull-test
  (:require
   [clojure.test :as t]
   [pyramid.pull :as p]))


(def entities
  (for [i (range 1000)]
    [:id i]))


(t/deftest many-entities
  (t/is (= (set entities)
           (:entities
            (p/pull-report
             {:id (into
                   {}
                   (map #(vector
                          (second %)
                          (hash-map (first %) (second %))))
                   entities)
              :all (vec entities)}
             [{:all [:id]}])))))
