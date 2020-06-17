(ns minimallist.core-test
  (:require [clojure.test :refer [deftest testing is are]]
            [minimallist.core :refer [valid?]]
            [minimallist.helper :as h]))

(deftest valid?-test
  (let [test-data [;; fn
                   (h/fn #(= 1 %))
                   [1]
                   [2]

                   ;; enum
                   (h/enum #{1 "2" :3})
                   [1 "2" :3]
                   [[1] 2 true false nil]

                   (h/enum #{nil false})
                   [nil false]
                   [true '()]

                   ;; and
                   (h/and (h/fn pos-int?)
                          (h/fn even?))
                   [2 4 6]
                   [0 :a -1 1 3]

                   ;; or
                   (h/or (h/fn pos-int?)
                         (h/fn even?))
                   [-2 0 1 2 3]
                   [-3 -1]

                   ;; set
                   (-> (h/set-of (h/fn int?))
                       (h/with-count (h/enum #{2 3})))
                   [#{1 2} #{1 2 3}]
                   [#{1 :a} [1 2 3] '(1 2) `(1 ~2) #{1} #{1 2 3 4}]

                   ;; map, entries
                   (h/map [:a (h/fn int?)]
                          [:b {:optional true} (h/fn string?)]
                          [(list 1 2 3) (h/fn string?)])
                   [{:a 1, :b "foo", (list 1 2 3) "you can count on me like ..."}
                    {:a 1, :b "bar", [1 2 3] "soleil !"}
                    {:a 1, [1 2 3] "soleil !"}]
                   [{:a 1, :b "foo"}
                    {:a 1, :b "foo", #{1 2 3} "bar"}
                    {:a 1, :b 'bar, [1 2 3] "soleil !"}]

                   ;; map, keys and values
                   (h/map-of (h/fn keyword?)
                             (h/fn int?))
                   [{} {:a 1, :b 2}]
                   [{:a 1, :b "2"} [[:a 1] [:b 2]] {true 1, false 2}]

                   ;; sequence, no collection type specified
                   (h/sequence-of (h/fn int?))
                   ['(1 2 3) [1 2 3] `(1 2 ~3)]
                   ['(1 :a) #{1 2 3} {:a 1, :b 2, :c 3}]

                   ;; sequence, with condition
                   (-> (h/sequence-of (h/fn int?))
                       (h/with-condition (h/fn (fn [coll] (= coll (reverse coll))))))
                   [[1] '(1 1) '[1 2 1]]
                   ['(1 2) '(1 2 3)]

                   ;; sequence as a list
                   (h/list-of (h/fn int?))
                   ['(1 2 3)]
                   ['(1 :a) [1 2 3] #{1 2 3}
                    #_`(1 2 ~3)]                            ; this is not a list in cljs

                   ;; sequence as a vector
                   (h/vector-of (h/fn int?))
                   [[1 2 3]]
                   [[1 :a] '(1 2 3) #{1 2 3} `(1 2 ~3)]

                   ;; sequence with size specified using a model
                   (-> (h/sequence) (h/with-count (h/enum #{2 3})))
                   ['(1 2) [1 "2"] `(1 ~"2") [1 "2" :3]]
                   [#{1 "a"} [1 "2" :3 :4]]

                   ;; sequence with entries (fixed size is implied)
                   (h/tuple (h/fn int?) (h/fn string?))
                   ['(1 "2") [1 "2"] `(1 ~"2")]
                   [#{1 "a"} [1 "2" :3]]

                   ;; alt
                   (h/alt [:int (h/fn int?)]
                          [:strings (h/cat (h/fn string?))])
                   [1 ["1"]]
                   [[1] "1" :1 [:1]]

                   ;; alt - inside a cat
                   (h/cat (h/fn int?)
                          (h/alt [:string (h/fn string?)]
                                 [:keyword (h/fn keyword?)]
                                 [:string-keyword (h/cat (h/fn string?)
                                                         (h/fn keyword?))])
                          (h/fn int?))
                   [[1 "2" 3] [1 :2 3] [1 "a" :b 3]]
                   [[1 ["a" :b] 3]]

                   ;; alt - inside a cat, but with :inline false on its cat entry
                   (h/cat (h/fn int?)
                          (h/alt [:string (h/fn string?)]
                                 [:keyword (h/fn keyword?)]
                                 [:string-keyword (-> (h/cat (h/fn string?)
                                                             (h/fn keyword?))
                                                      (h/not-inlined))])
                          (h/fn int?))
                   [[1 "2" 3] [1 :2 3] [1 ["a" :b] 3]]
                   [[1 "a" :b 3]]

                   ;; cat of cat, the inner cat is implicitly inlined
                   (-> (h/cat (h/fn int?)
                              (h/cat (h/fn int?)))
                       (h/in-vector))
                   [[1 2]]
                   [[1] [1 [2]] [1 2 3] '(1) '(1 2) '(1 (2)) '(1 2 3)]

                   ;; cat of cat, the inner cat is explicitly not inlined
                   (-> (h/cat (h/fn int?)
                              (-> (h/cat (h/fn int?))
                                  (h/not-inlined))))
                   [[1 [2]] '[1 (2)] '(1 (2))]
                   [[1] [1 2] [1 [2] 3]]

                   ;; repeat - no collection type specified
                   (h/repeat 0 2 (h/fn int?))
                   [[] [1] [1 2] '() '(1) '(2 3)]
                   [[1 2 3] '(1 2 3)]

                   ;; repeat - inside a vector
                   (h/in-vector (h/repeat 0 2 (h/fn int?)))
                   [[] [1] [1 2]]
                   [[1 2 3] '() '(1) '(2 3) '(1 2 3)]

                   ;; repeat - inside a list
                   (h/in-list (h/repeat 0 2 (h/fn int?)))
                   ['() '(1) '(2 3)]
                   [[] [1] [1 2] [1 2 3] '(1 2 3)]

                   ;; repeat - min > 0
                   (h/repeat 2 3 (h/fn int?))
                   [[1 2] [1 2 3]]
                   [[] [1] [1 2 3 4]]

                   ;; repeat - max = +Infinity
                   (h/repeat 2 ##Inf (h/fn int?))
                   [[1 2] [1 2 3] [1 2 3 4]]
                   [[] [1]]

                   ;; repeat - of a cat
                   (h/repeat 1 2 (h/cat (h/fn int?)
                                        (h/fn string?)))
                   [[1 "a"] [1 "a" 2 "b"]]
                   [[] [1] [1 2] [1 "a" 2 "b" 3 "c"]]

                   ;; repeat - of a cat with :inlined false
                   (h/repeat 1 2 (-> (h/cat (h/fn int?)
                                            (h/fn string?))
                                     (h/not-inlined)))
                   [[[1 "a"]] [[1 "a"] [2 "b"]] ['(1 "a") [2 "b"]]]
                   [[] [1] [1 2] [1 "a"] [1 "a" 2 "b"] [1 "a" 2 "b" 3 "c"]]

                   ;; let / ref
                   (h/let ['pos-even? (h/and (h/fn pos-int?)
                                             (h/fn even?))]
                          (h/ref 'pos-even?))
                   [2 4]
                   [-2 -1 0 1 3]

                   ;; let / ref - with structural recursion
                   (h/let ['hiccup (h/alt
                                     [:node (h/in-vector (h/cat (h/fn keyword?)
                                                                (h/? (h/map))
                                                                (h/* (h/not-inlined (h/ref 'hiccup)))))]
                                     [:primitive (h/or (h/fn nil?)
                                                       (h/fn boolean?)
                                                       (h/fn number?)
                                                       (h/fn string?))])]
                          (h/ref 'hiccup))
                   [nil
                    false
                    1
                    "hi"
                    [:div]
                    [:div {}]
                    [:div "hei" [:p "bonjour"]]
                    [:div {:a 1} "hei" [:p "bonjour"]]]
                   [{}
                    {:a 1}
                    ['div]
                    [:div {:a 1} "hei" [:p {} {} "bonjour"]]]

                   ;; let / ref - with recursion within a sequence
                   (h/let ['foo (h/cat (h/fn int?)
                                       (h/? (h/ref 'foo))
                                       (h/fn string?))]
                          (h/ref 'foo))
                   [[1 "hi"]
                    [1 1 "hi" "hi"]
                    [1 1 1 "hi" "hi" "hi"]]
                   [[1 1 "hi"]
                    [1 "hi" "hi"]
                    [1 1 :no "hi" "hi"]]]]


    (doseq [[model valid-coll invalid-coll] (partition 3 test-data)]
      (doseq [data valid-coll]
        (is (valid? model data)))
      (doseq [data invalid-coll]
        (is (not (valid? model data)))))))
