(ns minimallist.core-test
  (:require [clojure.test :refer [deftest testing is are]]
            [minimallist.core :refer [valid? explain describe undescribe] :as m]
            [minimallist.helper :as h]
            [minimallist.util :as util]))

(comment
  (#'m/sequence-descriptions {}
    ; [:cat [:+ pos-int?]
    ;       [:+ int?]]
    (h/cat (h/+ (h/fn pos-int?))
           (h/+ (h/fn int?)))
    [3 4 0 2])

  (#'m/sequence-descriptions {}
    ; [:repeat {:min 0, :max 2} int?]
    (h/repeat 0 2 (h/fn int?))
    (seq [1 2]))

  (#'m/sequence-descriptions {}
    ; [:alt [:ints     [:repeat {:min 0, :max 2} int?]]
    ;       [:keywords [:repeat {:min 0, :max 2} keyword?]]
    (h/alt [:ints (h/repeat 0 2 (h/fn int?))]
           [:keywords (h/repeat 0 2 (h/fn keyword?))])
    (seq [1 2]))

  (#'m/sequence-descriptions {}
    ; [:* int?]
    (h/* (h/fn int?))
    (seq [1 :2]))

  (#'m/sequence-descriptions {}
    ; [:+ int?]
    (h/+ (h/fn int?))
    (seq [1 2 3])))

(deftest valid?-test
  (let [test-data [;; fn
                   (h/fn #(= 1 %))
                   [1]
                   [2]

                   (-> (h/fn int?)
                       (h/with-condition (h/fn odd?)))
                   [1]
                   [2]

                   (-> (h/fn symbol?)
                       (h/with-condition (h/fn (complement #{'if 'val}))))
                   ['a]
                   ['if]

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
                   (h/map-of (h/vector (h/fn keyword?) (h/fn int?)))
                   [{} {:a 1, :b 2}]
                   [{:a 1, :b "2"} [[:a 1] [:b 2]] {true 1, false 2}]

                   ;; sequence, no collection type specified
                   (h/sequence-of (h/fn int?))
                   ['(1 2 3) [1 2 3] `(1 2 ~3)]
                   ['(1 :a) #{1 2 3} {:a 1, :b 2, :c 3}]

                   (h/sequence-of (h/fn char?))
                   [""  "hi" "hello"]
                   [[1 2 3]]

                   ;; sequence, with condition
                   (-> (h/sequence-of (h/fn int?))
                       (h/with-condition (h/fn (fn [coll] (= coll (reverse coll))))))
                   [[1] '(1 1) '[1 2 1]]
                   ['(1 2) '(1 2 3)]

                   ;; sequence as a list
                   (h/list-of (h/fn int?))
                   ['(1 2 3) `(1 2 ~3)]
                   ['(1 :a) [1 2 3] #{1 2 3}]

                   ;; sequence as a vector
                   (h/vector-of (h/fn int?))
                   [[1 2 3]]
                   [[1 :a] '(1 2 3) #{1 2 3} `(1 2 ~3)]

                   ;; sequence as a string
                   (h/string-of (h/enum (set "0123456789abcdef")))
                   ["03ab4c" "cafe"]
                   ["coffee" [1 :a] '(1 2 3) #{1 2 3} `(1 2 ~3)]

                   ;; sequence with size specified using a model
                   (-> (h/sequence-of (h/fn any?))
                       (h/with-count (h/enum #{2 3})))
                   ['(1 2) [1 "2"] `(1 ~"2") [1 "2" :3] "hi"]
                   [#{1 "a"} [1 "2" :3 :4] "hello"]

                   ;; sequence with entries (fixed size is implied)
                   (h/tuple (h/fn int?) (h/fn string?))
                   ['(1 "2") [1 "2"] `(1 ~"2")]
                   [#{1 "a"} [1 "2" :3]]

                   ;; sequence with entries in a string
                   (h/string-tuple (h/val \a) (h/enum #{\b \c}))
                   ["ab" "ac"]
                   [[\a \b] #{\a \b}]

                   ;; alt
                   (h/alt [:int (h/fn int?)]
                          [:strings (h/vector-of (h/fn string?))])
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

                   ;; cat & repeat - a color string
                   (-> (h/cat (h/val \#)
                              (h/repeat 6 6 (h/enum (set "0123456789abcdefABCDEF")))))
                   ["#000000" "#af4Ea5"]
                   ["000000" "#cafe" "#coffee"]

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

                   ;; repeat - inside a list
                   (h/in-list (h/repeat 0 2 (h/fn int?)))
                   ['() '(1) '(2 3)]
                   [[] [1] [1 2] [1 2 3] '(1 2 3)]

                   ;; repeat - inside a vector
                   (h/in-vector (h/repeat 0 2 (h/fn int?)))
                   [[] [1] [1 2]]
                   [[1 2 3] '() '(1) '(2 3) '(1 2 3)]

                   ;; repeat - inside a string
                   (h/in-string (h/repeat 4 6 (h/fn char?)))
                   ["hello"]
                   ["" "hi" [] [1] '(1 2 3)]

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

                   ;; char-cat & char-set
                   (-> (h/cat (h/char-cat "good")
                              (h/val \space)
                              (h/alt (h/char-cat "morning")
                                     (h/char-cat "afternoon")
                                     (h/repeat 3 10 (h/char-set "#?!@_*+%"))))
                       (h/in-string))
                   ["good morning" "good afternoon" "good #@*+?!"]
                   ["good" "good " "good day"]

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
                                     [:primitive (h/alt (h/fn nil?)
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
                    [1 1 :no "hi" "hi"]]

                   ; let / ref - with shadowed local model
                   (h/let ['foo (h/ref 'bar)
                           'bar (h/fn int?)]
                          (h/let ['bar (h/fn string?)]
                                 (h/ref 'foo)))
                   [1]
                   ["hi"]]]

    (doseq [[model valid-coll invalid-coll] (partition 3 test-data)]
      (doseq [data valid-coll]
        (is (valid? model data)))
      (doseq [data invalid-coll]
        (is (not (valid? model data))))))

  (is (thrown? #?(:clj Exception :cljs js/Object)
               (valid? (h/let [] (h/ref 'foo)) 'bar))))


(deftest describe-test
  (let [test-data [;; fn
                   (h/fn #(= 1 %))
                   [1 1
                    2 :invalid]

                   (-> (h/fn int?)
                       (h/with-condition (h/fn odd?)))
                   [1 1
                    2 :invalid]

                   (-> (h/fn symbol?)
                       (h/with-condition (h/fn (complement #{'if 'val}))))
                   ['a 'a
                    'if :invalid]

                   ;; enum
                   (h/enum #{1 "2" false nil})
                   [1 1
                    "2" "2"
                    false false
                    nil nil
                    true :invalid]

                   ;; and
                   (h/and (h/fn pos-int?)
                          (h/fn even?))
                   [0 :invalid
                    1 :invalid
                    2 2
                    3 :invalid
                    4 4]

                   ;; or
                   (h/or (h/fn int?)
                         (h/fn string?))
                   [1 1
                    "a" "a"
                    :a :invalid]

                   ;; set
                   (h/set-of (h/fn int?))
                   [#{1 2} [1 2]]

                   ;; map
                   (h/map [:a {:optional true} (h/fn int?)]
                          [:b (h/or (h/fn int?)
                                    (h/fn string?))])
                   [{:a 1, :b 2} {:a 1, :b 2}
                    {:a 1, :b "foo"} {:a 1, :b "foo"}
                    {:a 1, :b [1 2]} :invalid
                    ; missing optional entry
                    {:b 2} {:b 2}
                    ; missing entry
                    {:a 1} :invalid
                    ; extra entry
                    {:a 1, :b 2, :c 3} {:a 1, :b 2}]

                   ;; map-of - entry-model
                   (h/map-of (h/vector (h/fn keyword?) (h/fn int?)))
                   [{:a 1, :b 2} [[:a 1] [:b 2]]
                    {"a" 1} :invalid]

                   ;; map-of - real world use case
                   (h/map-of (h/alt [:symbol (h/vector (h/fn simple-symbol?) (h/fn keyword?))]
                                    [:keys (h/vector (h/val :keys) (h/vector-of (h/fn symbol?)))]
                                    [:as (h/vector (h/val :as) (h/fn simple-symbol?))]))
                   '[{first-name :first-name
                      last-name :last-name
                      :keys [foo bar]
                      :as foobar}
                     [[:symbol [first-name :first-name]]
                      [:symbol [last-name :last-name]]
                      [:keys [:keys [foo bar]]]
                      [:as [:as foobar]]]]

                   ;; sequence - :elements-model
                   (h/sequence-of (h/fn int?))
                   [[1 2 3] [1 2 3]
                    '(1 2 3) '(1 2 3)
                    `(1 2 3) '(1 2 3)
                    [1 "2" 3] :invalid]

                   ;; sequence - :elements-model with condition
                   (-> (h/sequence-of (h/fn int?))
                       (h/with-condition (h/fn (fn [coll] (= coll (reverse coll))))))
                   [[1 2 1] [1 2 1]
                    '(1 2 3) :invalid]

                   ;; sequence - :coll-type vector
                   (h/vector-of (h/fn any?))
                   [[1 2 3] [1 2 3]
                    '(1 2 3) :invalid
                    `(1 2 3) :invalid]

                   ;; sequence - :coll-type list
                   (h/list-of (h/fn any?))
                   [[1 2 3] :invalid
                    '(1 2 3) '(1 2 3)
                    `(1 2 3) '(1 2 3)]

                   ;; sequence - :entries
                   (h/tuple (h/fn int?) (h/fn string?))
                   [[1 "a"] [1 "a"]
                    [1 2] :invalid
                    [1] :invalid]

                   (h/tuple (h/fn int?)
                            [:text (h/fn string?)])
                   [[1 "a"] {:text "a"}]

                   (h/tuple [:number (h/fn int?)]
                            [:text (h/fn string?)])
                   [[1 "a"] {:number 1, :text "a"}]

                   ;; sequence - :count-model
                   (-> (h/sequence-of (h/fn any?))
                       (h/with-count (h/val 3)))
                   [[1 2] :invalid
                    [1 2 3] [1 2 3]
                    [1 2 3 4] :invalid
                    "12" :invalid
                    "123" (into [] "123")
                    "1234" :invalid]

                   ;; alt - not inside a sequence
                   (h/alt [:number (h/fn int?)]
                          [:sequence (h/vector-of (h/fn string?))])
                   [1 [:number 1]
                    ["1"] [:sequence ["1"]]
                    [1] :invalid
                    "1" :invalid]

                   ;; alt - inside a cat
                   (h/cat (h/fn int?)
                          (h/alt [:option1 (h/fn string?)]
                                 [:option2 (h/fn keyword?)]
                                 [:option3 (h/cat (h/fn string?)
                                                  (h/fn keyword?))])
                          (h/fn int?))
                   [[1 "2" 3] [1 [:option1 "2"] 3]
                    [1 :2 3] [1 [:option2 :2] 3]
                    [1 "a" :b 3] [1 [:option3 ["a" :b]] 3]
                    [1 ["a" :b] 3] :invalid]

                   ;; alt - inside a cat, but with :inline false on its cat entry
                   (h/cat (h/fn int?)
                          (h/alt [:option1 (h/fn string?)]
                                 [:option2 (h/fn keyword?)]
                                 [:option3 (h/not-inlined (h/cat (h/fn string?)
                                                                 (h/fn keyword?)))])
                          (h/fn int?))
                   [[1 "2" 3] [1 [:option1 "2"] 3]
                    [1 :2 3] [1 [:option2 :2] 3]
                    [1 "a" :b 3] :invalid
                    [1 ["a" :b] 3] [1 [:option3 ["a" :b]] 3]]

                   ;; cat of cat, the inner cat is implicitly inlined
                   (h/cat (h/fn int?)
                          (h/cat (h/fn int?)))
                   [[1 2] [1 [2]]
                    [1] :invalid
                    [1 [2]] :invalid
                    [1 2 3] :invalid]

                   ;; cat of cat, the inner cat is explicitly not inlined
                   (h/cat (h/fn int?)
                          (h/not-inlined (h/cat (h/fn int?))))
                   [[1 [2]] [1 [2]]
                    [1 '(2)] [1 [2]]
                    [1] :invalid
                    [1 2] :invalid
                    [1 [2] 3] :invalid]

                   ;; repeat - no collection type specified
                   (h/repeat 0 2 (h/fn int?))
                   [[] []
                    [1] [1]
                    [1 2] [1 2]
                    '() []
                    '(1) [1]
                    '(2 3) [2 3]
                    [1 2 3] :invalid
                    '(1 2 3) :invalid]

                   ;; repeat - inside a vector
                   (-> (h/repeat 0 2 (h/fn int?))
                       (h/in-vector))
                   [[1] [1]
                    '(1) :invalid]

                   ;; repeat - inside a list
                   (-> (h/repeat 0 2 (h/fn int?))
                       (h/in-list))
                   [[1] :invalid
                    '(1) [1]]

                   ;; repeat - min > 0
                   (h/repeat 2 3 (h/fn int?))
                   [[] :invalid
                    [1] :invalid
                    [1 2] [1 2]
                    [1 2 3] [1 2 3]
                    [1 2 3 4] :invalid]

                   ;; repeat - max = +Infinity
                   (h/repeat 2 ##Inf (h/fn int?))
                   [[] :invalid
                    [1] :invalid
                    [1 2] [1 2]
                    [1 2 3] [1 2 3]]

                   ;; repeat - of a cat
                   (h/repeat 1 2 (h/cat (h/fn int?)
                                        (h/fn string?)))
                   [[1 "a"] [[1 "a"]]
                    [1 "a" 2 "b"] [[1 "a"] [2 "b"]]
                    [] :invalid
                    [1] :invalid
                    [1 2] :invalid
                    [1 "a" 2 "b" 3 "c"] :invalid]

                   ;; repeat - of a cat with :inlined false
                   (h/repeat 1 2 (h/not-inlined (h/cat (h/fn int?)
                                                       (h/fn string?))))
                   [[[1 "a"]] [[1 "a"]]
                    [[1 "a"] [2 "b"]] [[1 "a"] [2 "b"]]
                    ['(1 "a") [2 "b"]] [[1 "a"] [2 "b"]]
                    [] :invalid
                    [1] :invalid
                    [1 2] :invalid
                    [1 "a"] :invalid
                    [1 "a" 2 "b"] :invalid
                    [1 "a" 2 "b" 3 "c"] :invalid]

                   ;; let / ref
                   (h/let ['pos-even? (h/and (h/fn pos-int?)
                                             (h/fn even?))]
                          (h/ref 'pos-even?))
                   [0 :invalid
                    1 :invalid
                    2 2
                    3 :invalid
                    4 4]]]

    (doseq [[model data-description-pairs] (partition 2 test-data)]
      (doseq [[data description] (partition 2 data-description-pairs)]
        (is (= [data (describe model data)]
               [data description])))))

  (is (thrown? #?(:clj Exception :cljs js/Object)
             (describe (h/let [] (h/ref 'foo)) 'bar))))
