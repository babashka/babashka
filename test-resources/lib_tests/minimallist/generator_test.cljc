(ns minimallist.generator-test
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.test.check.generators :as tcg]
            [clojure.string :as str]
            [minimallist.core :refer [valid?]]
            [minimallist.helper :as h]
            [minimallist.util :as util]
            [minimallist.generator :as mg :refer [gen fn-any? fn-int? fn-string? fn-char?
                                                  fn-symbol? fn-simple-symbol? fn-qualified-symbol?
                                                  fn-keyword? fn-simple-keyword? fn-qualified-keyword?]]))

(defn- path-test-visitor []
  ;; Testing using side effects.
  ;; A little ugly, but good enough for tests.
  (let [paths (atom [])]
    (fn
      ([] @paths)
      ([model stack path]
       (swap! paths conj path)
       model))))

(deftest postwalk-visit-order-test
  (are [model expected-paths]
    (let [visitor (path-test-visitor)]
      (mg/postwalk model visitor)   ; Create side effects
      (= (visitor) expected-paths)) ; Collect and compare the side effects

    (h/let ['leaf (h/fn int?)
            'tree (h/ref 'leaf)]
           (h/ref 'tree))
    [[:bindings 'leaf]
     [:bindings 'tree]
     [:body]
     []]

    (h/let ['root (h/let ['leaf (h/fn int?)
                          'tree (h/ref 'leaf)]
                         (h/ref 'tree))]
           (h/ref 'root))
    [[:bindings 'root :bindings 'leaf]
     [:bindings 'root :bindings 'tree]
     [:bindings 'root :body]
     [:bindings 'root]
     [:body]
     []]

    (h/let ['leaf (h/fn int?)
            'root (h/let ['tree (h/ref 'leaf)]
                         (h/ref 'tree))]
           (h/ref 'root))
    [[:bindings 'leaf]
     [:bindings 'root :bindings 'tree]
     [:bindings 'root :body]
     [:bindings 'root]
     [:body]
     []]

    ; test of no visit more than once
    (h/let ['leaf (h/fn int?)
            'tree (h/tuple (h/ref 'leaf) (h/ref 'leaf))]
           (h/ref 'tree))
    [[:bindings 'leaf]
     [:bindings 'tree :entries 0 :model]
     [:bindings 'tree :entries 1 :model]
     [:bindings 'tree]
     [:body]
     []]

    ; test of no visit more than once, infinite loop otherwise
    (h/let ['leaf (h/fn int?)
            'tree (h/tuple (h/ref 'tree) (h/ref 'leaf))]
           (h/ref 'tree))
    [[:bindings 'tree :entries 0 :model]
     [:bindings 'leaf]
     [:bindings 'tree :entries 1 :model]
     [:bindings 'tree]
     [:body]
     []]

    #__))

(deftest assoc-leaf-distance-visitor-test
  (are [model expected-walked-model]
    (= (-> model
           (mg/postwalk mg/assoc-leaf-distance-visitor)
           (util/walk-map-dissoc :fn))
       expected-walked-model)

    ; Recursive data-structure impossible to generate
    ; This one is trying to bring the generator function in an infinite loop.
    (h/let ['loop (h/ref 'loop)]
           (h/ref 'loop))
    {:type :let
     :bindings {'loop {:type :ref
                       :key 'loop}}
     :body {:type :ref
            :key 'loop}}

    ; Recursive data-structure impossible to generate
    (h/let ['leaf (h/fn int?)
            'tree (h/tuple (h/ref 'tree) (h/ref 'leaf))]
           (h/ref 'tree))
    {:type :let
     :bindings {'leaf {:type :fn
                       ::mg/leaf-distance 0}
                'tree {:type :sequence
                       :entries [{:model {:type :ref
                                          :key 'tree}}
                                 {:model {:type :ref
                                          :key 'leaf
                                          ::mg/leaf-distance 1}}]}}
     :body {:type :ref
            :key 'tree}}

    ; Recursive data-structure impossible to generate
    (h/let ['rec-map (h/map [:a (h/fn int?)]
                            [:b (h/ref 'rec-map)])]
           (h/ref 'rec-map))
    {:type :let
     :bindings {'rec-map {:type :map
                          :entries [{:key :a
                                     :model {:type :fn
                                             ::mg/leaf-distance 0}}
                                    {:key :b
                                     :model {:type :ref
                                             :key 'rec-map}}]}}
     :body {:type :ref
            :key 'rec-map}}

    ; Recursive data-structure which can be generated
    (h/let ['leaf (h/fn int?)
            'tree (h/alt (h/ref 'tree) (h/ref 'leaf))]
           (h/ref 'tree))
    {:type :let
     :bindings {'leaf {:type :fn
                       ::mg/leaf-distance 0}
                'tree {:type :alt
                       :entries [{:model {:type :ref
                                          :key 'tree}}
                                 {:model {:type :ref
                                          :key 'leaf
                                          ::mg/leaf-distance 1}}]
                       ::mg/leaf-distance 2}}
     :body {:type :ref
            :key 'tree
            ::mg/leaf-distance 3}
     ::mg/leaf-distance 4}

    (h/let ['rec-map (h/map [:a (h/fn int?)]
                            [:b {:optional true} (h/ref 'rec-map)])]
           (h/ref 'rec-map))
    {:type :let
     :bindings {'rec-map {:type :map
                          :entries [{:key :a
                                     :model {:type :fn
                                             ::mg/leaf-distance 0}}
                                    {:key :b
                                     :optional true
                                     :model {:type :ref
                                             :key 'rec-map}}]
                          ::mg/leaf-distance 1}}
     :body {:type :ref
            :key 'rec-map
            ::mg/leaf-distance 2}
     ::mg/leaf-distance 3}

    #__))


(deftest assoc-min-cost-visitor-test
  (are [model expected-walked-model]
    (= (-> model
           (mg/postwalk mg/assoc-min-cost-visitor)
           (util/walk-map-dissoc :fn))
       expected-walked-model)

    (h/tuple (h/fn int?) (h/fn string?))
    {:type :sequence
     :entries [{:model {:type :fn
                        ::mg/min-cost 1}}
               {:model {:type :fn
                        ::mg/min-cost 1}}]
     ::mg/min-cost 3}

    (h/cat (h/fn int?) (h/fn string?))
    {:type :cat
     :entries [{:model {:type :fn
                        ::mg/min-cost 1}}
               {:model {:type :fn
                        ::mg/min-cost 1}}]
     ::mg/min-cost 3}

    (h/in-vector (h/cat (h/fn int?) (h/fn string?)))
    {:type :cat
     :coll-type :vector
     :entries [{:model {:type :fn
                        ::mg/min-cost 1}}
               {:model {:type :fn
                        ::mg/min-cost 1}}]
     ::mg/min-cost 3}

    (h/not-inlined (h/cat (h/fn int?) (h/fn string?)))
    {:type :cat
     :inlined false
     :entries [{:model {:type :fn
                        ::mg/min-cost 1}}
               {:model {:type :fn
                        ::mg/min-cost 1}}]
     ::mg/min-cost 3}

    (h/map [:a (h/fn int?)]
           [:b {:optional true} (h/fn int?)])
    {:type :map
     :entries [{:key :a
                :model {:type :fn
                        ::mg/min-cost 1}}
               {:key :b
                :optional true
                :model {:type :fn
                        ::mg/min-cost 1}}]
     ::mg/min-cost 2}

    (h/map-of (h/vector (h/fn keyword?) (h/fn int?)))
    {:type :map-of
     :entry-model {:type :sequence
                   :coll-type :vector
                   :entries [{:model {:type :fn
                                      ::mg/min-cost 1}}
                             {:model {:type :fn
                                      ::mg/min-cost 1}}]
                   ::mg/min-cost 3}
     ::mg/min-cost 1}

    (-> (h/map-of (h/vector (h/fn keyword?) (h/fn int?)))
        (h/with-count (h/enum #{3 4})))
    {:type :map-of
     :entry-model {:type :sequence
                   :coll-type :vector
                   :entries [{:model {:type :fn
                                      ::mg/min-cost 1}}
                             {:model {:type :fn
                                      ::mg/min-cost 1}}]
                   ::mg/min-cost 3}
     :count-model {:type :enum
                   :values #{3 4}}
     ::mg/min-cost 7}

    (h/set-of (h/fn any?))
    {:type :set-of
     :elements-model {:type :fn
                      ::mg/min-cost 1}
     ::mg/min-cost 1}

    (-> (h/set-of (h/fn any?))
        (h/with-count (h/val 3)))
    {:type :set-of
     :elements-model {:type :fn
                      ::mg/min-cost 1}
     :count-model {:type :enum
                   :values #{3}}
     ::mg/min-cost 4}

    (h/let ['foo (-> (h/set-of (h/fn int?))
                     (h/with-count (h/val 3)))]
           (h/ref 'foo))
    {:type :let
     :bindings {'foo {:type :set-of
                      :count-model {:type :enum
                                    :values #{3}}
                      :elements-model {:type :fn
                                       ::mg/min-cost 1}
                      ::mg/min-cost 4}}
     :body {:type :ref
            :key 'foo
            ::mg/min-cost 4}
     ::mg/min-cost 4}

    #__))

(deftest budget-split-gen-test
  (is (every? (fn [[a b c]]
                (and (<= 0 a 5)
                     (<= 5 b 10)
                     (<= 10 c 15)))
              (-> (#'mg/budget-split-gen 20.0 [0 5 10])
                  tcg/sample)))
  (is (every? #(= % [5 10 10])
              (-> (#'mg/budget-split-gen 20.0 [5 10 10])
                  tcg/sample)))
  (is (every? empty?
              (-> (#'mg/budget-split-gen 10.0 [])
                  tcg/sample))))

(comment
  ;; For occasional hand testing

  (tcg/sample (gen (-> (h/set-of fn-any?)
                       (h/with-count (h/enum #{1 2 3 10}))
                       (h/with-condition (h/fn (comp #{1 2 3} count))))))

  (tcg/sample (gen (h/map-of (h/vector fn-int? fn-simple-symbol?))))

  (tcg/sample (gen (-> (h/map [:a fn-int?])
                       (h/with-optional-entries [:b fn-string?]))))

  (tcg/sample (gen (h/sequence-of fn-int?)))

  (tcg/sample (gen (h/tuple fn-int? fn-string?)))

  (tcg/sample (gen (h/cat fn-int? fn-string?)))

  (tcg/sample (gen (h/repeat 2 3 fn-int?)))

  (tcg/sample (gen (h/repeat 2 3 (h/cat fn-int? fn-string?))))

  (tcg/sample (gen (h/let ['int? fn-int?
                           'string? fn-string?
                           'int-string? (h/cat (h/ref 'int?) (h/ref 'string?))]
                          (h/repeat 2 3 (h/ref 'int-string?)))))

  (tcg/sample (gen (-> (h/set-of fn-int?)
                       (h/with-condition (h/fn (fn [coll]
                                                 (or (empty? coll)
                                                     (some even? coll))))))))

  (tcg/sample (gen (-> (h/set-of fn-any?)
                       (h/with-count (h/enum #{1 2 3 10}))
                       (h/with-condition (h/fn (comp #{1 2 3} count))))))

  (tcg/sample (gen (h/let ['node (h/set-of (h/ref 'node))]
                          (h/ref 'node))))

  (tcg/sample (gen (h/let ['node (h/map-of (h/vector fn-int? (h/ref 'node)))]
                          (h/ref 'node)) 50))

  (tcg/sample (gen (h/let ['node (h/map-of (h/vector fn-keyword? (h/ref 'node)))]
                          (h/ref 'node)) 100) 1)

  (tcg/sample (gen (h/map [:a fn-int?])))

  (tcg/sample (gen (-> (h/map [:a fn-int?])
                       (h/with-optional-entries [:b fn-string?]))))

  (tcg/sample (gen (h/cat (h/vector-of fn-int?)
                          (h/vector-of fn-int?)) 20))

  (tcg/sample (gen (h/repeat 5 10 fn-int?)))

  (tcg/sample (gen fn-symbol?))
  (tcg/sample (gen fn-simple-symbol?))
  (tcg/sample (gen fn-qualified-symbol?))

  (tcg/sample (gen fn-keyword?))
  (tcg/sample (gen fn-simple-keyword?))
  (tcg/sample (gen fn-qualified-keyword?))

  (tcg/sample (gen (-> (h/cat (h/char-cat "good")
                              (h/val \space)
                              (h/alt (h/char-cat "morning")
                                     (h/char-cat "afternoon")
                                     (h/repeat 3 10 (h/char-set "#?!@_*+%"))))
                       (h/in-string)))
              100)


  #__)

(deftest gen-test
  (let [model fn-string?]
    (is (every? (partial valid? model)
                (tcg/sample (gen model)))))

  (let [model (h/enum #{:1 2 "3"})]
    (is (every? (partial valid? model)
                (tcg/sample (gen model)))))

  (let [model (-> (h/set-of fn-int?)
                  (h/with-condition (h/fn (fn [coll]
                                            (or (empty? coll)
                                                (some even? coll))))))]
    (is (every? (partial valid? model)
                (tcg/sample (gen model)))))

  (let [model (-> (h/set-of fn-any?)
                  (h/with-count (h/enum #{1 2 3 10}))
                  (h/with-condition (h/fn (comp #{1 2 3} count))))]
    (is (every? (partial valid? model)
                (tcg/sample (gen model)))))

  (let [model (h/map-of (h/vector fn-int? fn-string?))]
    (is (every? (partial valid? model)
                (tcg/sample (gen model)))))

  (let [model (-> (h/map [:a fn-int?])
                  (h/with-optional-entries [:b fn-string?])
                  (h/with-entries [:c fn-int?])
                  (h/with-optional-entries [:d fn-string?]))
        sample (tcg/sample (gen model) 100)]
    (is (and (every? (partial valid? model) sample)
             (every? (fn [element] (contains? element :a)) sample)
             (some (fn [element] (contains? element :b)) sample)
             (some (fn [element] (not (contains? element :b))) sample)
             (every? (fn [element] (contains? element :c)) sample)
             (some (fn [element] (contains? element :d)) sample)
             (some (fn [element] (not (contains? element :d))) sample))))

  (let [model (h/sequence-of fn-int?)]
    (is (every? (partial valid? model)
                (tcg/sample (gen model)))))

  (let [model (h/tuple fn-int? fn-string?)
        sample (tcg/sample (gen model) 100)]
    (is (and (every? (partial valid? model) sample)
             (some list? sample)
             (some vector? sample))))

  (let [model (h/list fn-int? fn-string?)
        sample (tcg/sample (gen model))]
    (is (and (every? (partial valid? model) sample)
             (every? list? sample))))

  (let [model (h/vector fn-int? fn-string?)
        sample (tcg/sample (gen model))]
    (is (and (every? (partial valid? model) sample)
             (every? vector? sample))))

  (let [model (h/string-tuple fn-char? fn-char?)
        sample (tcg/sample (gen model))]
    (is (and (every? (partial valid? model) sample)
             (every? string? sample))))

  (let [model (h/in-list (h/cat fn-int? fn-string?))
        sample (tcg/sample (gen model))]
    (is (and (every? (partial valid? model) sample)
             (every? list? sample))))

  (let [model (h/in-vector (h/cat fn-int? fn-string?))
        sample (tcg/sample (gen model))]
    (is (and (every? (partial valid? model) sample)
             (every? vector? sample))))

  (let [model (h/in-string (h/cat fn-char? fn-char?))
        sample (tcg/sample (gen model))]
    (is (and (every? (partial valid? model) sample)
             (every? string? sample))))

  (let [model (h/alt fn-int? fn-string?)]
    (is (every? (partial valid? model)
                (tcg/sample (gen model)))))

  (let [model (h/cat fn-int? fn-string?)]
    (is (every? (partial valid? model)
                (tcg/sample (gen model)))))

  (let [model (h/repeat 2 3 fn-int?)]
    (is (every? (partial valid? model)
                (tcg/sample (gen model)))))

  (let [model (h/repeat 2 3 (h/cat fn-int? fn-string?))]
    (is (every? (partial valid? model)
                (tcg/sample (gen model)))))

  (let [model (h/not-inlined (h/cat fn-int?))]
    (is (every? (partial valid? model)
                (tcg/sample (gen model)))))

  (let [model (h/not-inlined (h/repeat 1 2 fn-int?))]
    (is (every? (partial valid? model)
                (tcg/sample (gen model)))))

  (let [model (h/let ['int? fn-int?
                      'string? fn-string?
                      'int-string? (h/cat (h/ref 'int?) (h/ref 'string?))]
                (h/repeat 2 3 (h/ref 'int-string?)))]
    (is (every? (partial valid? model)
                (tcg/sample (gen model)))))

  ;; Budget-based limit on model choice.
  (let [model (h/let ['tree (h/alt [:leaf fn-int?]
                                   [:branch (h/vector (h/ref 'tree)
                                                      (h/ref 'tree))])]
                (h/ref 'tree))]
    (is (every? (partial valid? model)
                (tcg/sample (gen model)))))

  ;; Budget-based limit on variable set size.
  (let [model (h/let ['node (h/set-of (h/ref 'node))]
                (h/ref 'node))]
    (is (every? (partial valid? model)
                (tcg/sample (gen model)))))

  ;; Budget-based limit on variable sequence size.
  (let [model (h/let ['node (h/vector-of (h/ref 'node))]
                (h/ref 'node))]
    (is (every? (partial valid? model)
                (tcg/sample (gen model)))))

  ;; Budget-based limit on variable map size.
  (let [model (h/let ['node (h/map-of (h/vector fn-int? (h/ref 'node)))]
                (h/ref 'node))]
    (is (every? (partial valid? model)
                (tcg/sample (gen model)))))

  ;; Budget-based limit on optional entries in a map.
  (let [model (h/let ['node (-> (h/map [:a fn-int?])
                                (h/with-optional-entries [:x (h/ref 'node)]
                                                         [:y (h/ref 'node)]
                                                         [:z (h/ref 'node)]))]
                (h/ref 'node))]
    (is (every? (partial valid? model)
                (tcg/sample (gen model)))))

  ;;; Budget-based limit on number of occurrences in a repeat.
  ;(let [model (h/let ['node (h/repeat 0 1 (h/ref 'node))]
  ;              (h/ref 'node))]
  ;  (is (every? (partial valid? model)
  ;              (tcg/sample (gen model)))))

  ;; Model impossible to generate.
  (let [model (h/let ['node (h/map [:a (h/ref 'node)])]
                (h/ref 'node))]
    (is (thrown? #?(:clj Exception :cljs js/Object) (tcg/sample (gen model)))))

  ;; Model impossible to generate.
  (let [model (h/let ['node (h/tuple (h/ref 'node))]
                (h/ref 'node))]
    (is (thrown? #?(:clj Exception :cljs js/Object) (tcg/sample (gen model)))))

  ;; Model impossible to generate.
  (let [model (h/let ['node (h/cat (h/ref 'node))]
                (h/ref 'node))]
    (is (thrown? #?(:clj Exception :cljs js/Object) (tcg/sample (gen model)))))

  ;; Model impossible to generate.
  (let [model (h/let ['node (h/cat (h/ref 'node))]
                (h/ref 'node))]
    (is (thrown? #?(:clj Exception :cljs js/Object) (tcg/sample (gen model)))))

  (let [model (h/let ['node (h/repeat 1 2 (h/ref 'node))]
                (h/ref 'node))]
    (is (thrown? #?(:clj Exception :cljs js/Object) (tcg/sample (gen model))))))

;; TODO: [later] reuse the cat-ipsum model for parsing the output.

;; TODO: in the :alt node, introduce a property :occurrence for the generator.
;; TODO: generate models, use them to generate data, should not stack overflow.
