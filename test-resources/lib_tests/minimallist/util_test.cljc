(ns minimallist.util-test
  (:require [clojure.test :refer [deftest testing is are]]
            [minimallist.util :as util]
            [minimallist.helper :as h]))

(deftest reduce-update-test
  (let [m {:a 1
           :b 5}
        f (fn [acc elm]
            (let [elm10 (* elm 10)]
              [(conj acc elm10) elm10]))]
    (is (= (-> [[] m]
               (util/reduce-update :a f)
               (util/reduce-update :b f))
           [[10 50] {:a 10, :b 50}]))))

(deftest reduce-update-in-test
  (let [m {:a {:x 1, :y 2}
           :b [3 4 5]}
        f (fn [acc elm]
            (let [elm10 (* elm 10)]
              [(conj acc elm10) elm10]))]
    (is (= (-> [[] m]
               (util/reduce-update-in [:a :x] f)
               (util/reduce-update-in [:b 2] f))
           [[10 50] {:a {:x 10, :y 2}, :b [3 4 50]}]))))

(deftest reduce-mapv
  (let [m {:a {:x 1, :y 2}
           :b [3 4 5]}
        f (fn [acc elm]
            (let [elm10 (* elm 10)]
              [(conj acc elm10) elm10]))]
    (is (= (util/reduce-update [[] m] :b (partial util/reduce-mapv f))
           [[30 40 50] {:a {:x 1, :y 2}, :b [30 40 50]}]))))

(deftest iterate-while-different-test
  (let [inc-up-to-10 (fn [x] (cond-> x (< x 10) inc))]
    (is (= (util/iterate-while-different inc-up-to-10 0 0) 0))
    (is (= (util/iterate-while-different inc-up-to-10 0 5) 5))
    (is (= (util/iterate-while-different inc-up-to-10 0 10) 10))
    (is (= (util/iterate-while-different inc-up-to-10 0 15) 10))

    (is (= (util/iterate-while-different inc-up-to-10 7 2) 9))
    (is (= (util/iterate-while-different inc-up-to-10 7 3) 10))
    (is (= (util/iterate-while-different inc-up-to-10 7 4) 10))

    (is (= (util/iterate-while-different inc-up-to-10 12 0) 12))
    (is (= (util/iterate-while-different inc-up-to-10 12 3) 12))

    (is (= (util/iterate-while-different inc-up-to-10 0 ##Inf) 10))
    (is (= (util/iterate-while-different inc-up-to-10 10 ##Inf) 10))
    (is (= (util/iterate-while-different inc-up-to-10 15 ##Inf) 15))))
