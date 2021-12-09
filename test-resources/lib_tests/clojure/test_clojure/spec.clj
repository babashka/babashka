;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.test-clojure.spec
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer :all]))

(set! *warn-on-reflection* true)

(defmacro result-or-ex [x]
  `(try
     ~x
     (catch Throwable t#
       (.getName (class t#)))))

(def even-count? #(even? (count %)))

(defn submap?
  "Is m1 a subset of m2?"
  [m1 m2]
  (if (and (map? m1) (map? m2))
    (every? (fn [[k v]] (and (contains? m2 k)
                          (submap? v (get m2 k))))
      m1)
    (= m1 m2)))

(deftest conform-explain
  (let [a (s/and #(> % 5) #(< % 10))
        o (s/or :s string? :k keyword?)
        c (s/cat :a string? :b keyword?)
        either (s/alt :a string? :b keyword?)
        star (s/* keyword?)
        plus (s/+ keyword?)
        opt (s/? keyword?)
        andre (s/& (s/* keyword?) even-count?)
        andre2 (s/& (s/* keyword?) #{[:a]})
        m (s/map-of keyword? string?)
        mkeys (s/map-of (s/and keyword? (s/conformer name)) any?)
        mkeys2 (s/map-of (s/and keyword? (s/conformer name)) any? :conform-keys true)
        s (s/coll-of (s/spec (s/cat :tag keyword? :val any?)) :kind list?)
        v (s/coll-of keyword? :kind vector?)
        coll (s/coll-of keyword?)
        lrange (s/int-in 7 42)
        drange (s/double-in :infinite? false :NaN? false :min 3.1 :max 3.2)
        irange (s/inst-in #inst "1939" #inst "1946")
        ]
    (are [spec x conformed ed]
      (let [co (result-or-ex (s/conform spec x))
            e (result-or-ex (::s/problems (s/explain-data spec x)))]
        (when (not= conformed co) (println "conform fail\n\texpect=" conformed "\n\tactual=" co))
        (when (not (every? true? (map submap? ed e)))
          (println "explain failures\n\texpect=" ed "\n\tactual failures=" e "\n\tsubmap?=" (map submap? ed e)))
        (and (= conformed co) (every? true? (map submap? ed e))))

      lrange 7 7 nil
      lrange 8 8 nil
      lrange 42 ::s/invalid [{:pred '(clojure.core/fn [%] (clojure.spec.alpha/int-in-range? 7 42 %)), :val 42}]

      irange #inst "1938" ::s/invalid [{:pred '(clojure.core/fn [%] (clojure.spec.alpha/inst-in-range? #inst "1939-01-01T00:00:00.000-00:00" #inst "1946-01-01T00:00:00.000-00:00" %)), :val #inst "1938"}]
      irange #inst "1942" #inst "1942" nil
      irange #inst "1946" ::s/invalid [{:pred '(clojure.core/fn [%] (clojure.spec.alpha/inst-in-range? #inst "1939-01-01T00:00:00.000-00:00" #inst "1946-01-01T00:00:00.000-00:00" %)), :val #inst "1946"}]

      drange 3.0 ::s/invalid [{:pred '(clojure.core/fn [%] (clojure.core/<= 3.1 %)), :val 3.0}]
      drange 3.1 3.1 nil
      drange 3.2 3.2 nil
      drange Double/POSITIVE_INFINITY ::s/invalid [{:pred '(clojure.core/fn [%] (clojure.core/not (Double/isInfinite %))), :val Double/POSITIVE_INFINITY}]
      ;; can't use equality-based test for Double/NaN
      ;; drange Double/NaN ::s/invalid {[] {:pred '(clojure.core/fn [%] (clojure.core/not (Double/isNaN %))), :val Double/NaN}}

      keyword? :k :k nil
      keyword? nil ::s/invalid [{:pred `keyword? :val nil}]
      keyword? "abc" ::s/invalid [{:pred `keyword? :val "abc"}]

      a 6 6 nil
      a 3 ::s/invalid '[{:pred (clojure.core/fn [%] (clojure.core/> % 5)), :val 3}]
      a 20 ::s/invalid '[{:pred (clojure.core/fn [%] (clojure.core/< % 10)), :val 20}]
      a nil "java.lang.NullPointerException" "java.lang.NullPointerException"
      a :k "java.lang.ClassCastException" "java.lang.ClassCastException"

      o "a" [:s "a"] nil
      o :a [:k :a] nil
      o 'a ::s/invalid '[{:pred clojure.core/string?, :val a, :path [:s]} {:pred clojure.core/keyword?, :val a :path [:k]}]

      c nil ::s/invalid '[{:reason "Insufficient input", :pred clojure.core/string?, :val (), :path [:a]}]
      c [] ::s/invalid '[{:reason "Insufficient input", :pred clojure.core/string?, :val (), :path [:a]}]
      c [:a] ::s/invalid '[{:pred clojure.core/string?, :val :a, :path [:a], :in [0]}]
      c ["a"] ::s/invalid '[{:reason "Insufficient input", :pred clojure.core/keyword?, :val (), :path [:b]}]
      c ["s" :k] '{:a "s" :b :k} nil
      c ["s" :k 5] ::s/invalid '[{:reason "Extra input", :pred (clojure.spec.alpha/cat :a clojure.core/string? :b clojure.core/keyword?), :val (5)}]
      (s/cat) nil {} nil
      (s/cat) [5] ::s/invalid '[{:reason "Extra input", :pred (clojure.spec.alpha/cat), :val (5), :in [0]}]

      either nil ::s/invalid '[{:reason "Insufficient input", :pred (clojure.spec.alpha/alt :a clojure.core/string? :b clojure.core/keyword?), :val () :via []}]
      either [] ::s/invalid '[{:reason "Insufficient input", :pred (clojure.spec.alpha/alt :a clojure.core/string? :b clojure.core/keyword?), :val () :via []}]
      either [:k] [:b :k] nil
      either ["s"] [:a "s"] nil
      either [:b "s"] ::s/invalid '[{:reason "Extra input", :pred (clojure.spec.alpha/alt :a clojure.core/string? :b clojure.core/keyword?), :val ("s") :via []}]

      star nil [] nil
      star [] [] nil
      star [:k] [:k] nil
      star [:k1 :k2] [:k1 :k2] nil
      star [:k1 :k2 "x"] ::s/invalid '[{:pred clojure.core/keyword?, :val "x" :via []}]
      star ["a"] ::s/invalid '[{:pred clojure.core/keyword?, :val "a" :via []}]

      plus nil ::s/invalid '[{:reason "Insufficient input", :pred clojure.core/keyword?, :val () :via []}]
      plus [] ::s/invalid '[{:reason "Insufficient input", :pred clojure.core/keyword?, :val () :via []}]
      plus [:k] [:k] nil
      plus [:k1 :k2] [:k1 :k2] nil
      plus [:k1 :k2 "x"] ::s/invalid '[{:pred clojure.core/keyword?, :val "x", :in [2]}]
      plus ["a"] ::s/invalid '[{:pred clojure.core/keyword?, :val "a" :via []}]

      opt nil nil nil
      opt [] nil nil
      opt :k ::s/invalid '[{:pred (clojure.core/fn [%] (clojure.core/or (clojure.core/nil? %) (clojure.core/sequential? %))), :val :k}]
      opt [:k] :k nil
      opt [:k1 :k2] ::s/invalid '[{:reason "Extra input", :pred (clojure.spec.alpha/? clojure.core/keyword?), :val (:k2)}]
      opt [:k1 :k2 "x"] ::s/invalid '[{:reason "Extra input", :pred (clojure.spec.alpha/? clojure.core/keyword?), :val (:k2 "x")}]
      opt ["a"] ::s/invalid '[{:pred clojure.core/keyword?, :val "a"}]

      andre nil nil nil
      andre [] nil nil
      andre :k :clojure.spec.alpha/invalid '[{:pred (clojure.core/fn [%] (clojure.core/or (clojure.core/nil? %) (clojure.core/sequential? %))), :val :k}]
      andre [:k] ::s/invalid '[{:pred clojure.test-clojure.spec/even-count?, :val [:k]}]
      andre [:j :k] [:j :k] nil

      andre2 nil :clojure.spec.alpha/invalid [{:pred #{[:a]}, :val []}]
      andre2 [] :clojure.spec.alpha/invalid [{:pred #{[:a]}, :val []}]
      andre2 [:a] [:a] nil

      m nil ::s/invalid '[{:pred clojure.core/map?, :val nil}]
      m {} {} nil
      m {:a "b"} {:a "b"} nil

      mkeys nil ::s/invalid '[{:pred clojure.core/map?, :val nil}]
      mkeys {} {} nil
      mkeys {:a 1 :b 2} {:a 1 :b 2} nil

      mkeys2 nil ::s/invalid '[{:pred clojure.core/map?, :val nil}]
      mkeys2 {} {} nil
      mkeys2 {:a 1 :b 2} {"a" 1 "b" 2} nil

      s '([:a 1] [:b "2"]) '({:tag :a :val 1} {:tag :b :val "2"}) nil

      v [:a :b] [:a :b] nil
      v '(:a :b) ::s/invalid '[{:pred clojure.core/vector? :val (:a :b)}]

      coll nil ::s/invalid '[{:path [], :pred clojure.core/coll?, :val nil, :via [], :in []}]
      coll [] [] nil
      coll [:a] [:a] nil
      coll [:a :b] [:a :b] nil
      coll (map identity [:a :b]) '(:a :b) nil
      ;;coll [:a "b"] ::s/invalid '[{:pred (coll-checker keyword?), :val [:a b]}]
      )))

(deftest describing-evaled-specs
  (let [sp #{1 2}]
    (is (= (s/describe sp) (s/form sp) sp)))

  (is (= (s/describe odd?) 'odd?))
  (is (= (s/form odd?) 'clojure.core/odd?))

  #_(is (= (s/describe #(odd? %)) ::s/unknown))
  #_(is (= (s/form #(odd? %)) ::s/unknown)))

(defn check-conform-unform [spec vals expected-conforms]
  (let [actual-conforms (map #(s/conform spec %) vals)
        unforms (map #(s/unform spec %) actual-conforms)]
    (is (= actual-conforms expected-conforms))
    (is (= vals unforms))))

(deftest nilable-conform-unform
  (check-conform-unform
    (s/nilable int?)
    [5 nil]
    [5 nil])
  (check-conform-unform
    (s/nilable (s/or :i int? :s string?))
    [5 "x" nil]
    [[:i 5] [:s "x"] nil]))

(deftest nonconforming-conform-unform
  (check-conform-unform
    (s/nonconforming (s/or :i int? :s string?))
    [5 "x"]
    [5 "x"]))

(deftest coll-form
  (are [spec form]
    (= (s/form spec) form)
    (s/map-of int? any?)
    '(clojure.spec.alpha/map-of clojure.core/int? clojure.core/any?)

    (s/coll-of int?)
    '(clojure.spec.alpha/coll-of clojure.core/int?)

    (s/every-kv int? int?)
    '(clojure.spec.alpha/every-kv clojure.core/int? clojure.core/int?)

    (s/every int?)
    '(clojure.spec.alpha/every clojure.core/int?)

    (s/coll-of (s/tuple (s/tuple int?)))
    '(clojure.spec.alpha/coll-of (clojure.spec.alpha/tuple (clojure.spec.alpha/tuple clojure.core/int?)))

    (s/coll-of int? :kind vector?)
    '(clojure.spec.alpha/coll-of clojure.core/int? :kind clojure.core/vector?)

    (s/coll-of int? :gen #(gen/return [1 2]))
    '(clojure.spec.alpha/coll-of clojure.core/int? :gen (fn* [] (gen/return [1 2])))))

(deftest coll-conform-unform
  (check-conform-unform
    (s/coll-of (s/or :i int? :s string?))
    [[1 "x"]]
    [[[:i 1] [:s "x"]]])
  (check-conform-unform
    (s/every (s/or :i int? :s string?))
    [[1 "x"]]
    [[1 "x"]])
  (check-conform-unform
    (s/map-of int? (s/or :i int? :s string?))
    [{10 10 20 "x"}]
    [{10 [:i 10] 20 [:s "x"]}])
  (check-conform-unform
    (s/map-of (s/or :i int? :s string?) int? :conform-keys true)
    [{10 10 "x" 20}]
    [{[:i 10] 10 [:s "x"] 20}])
  (check-conform-unform
    (s/every-kv int? (s/or :i int? :s string?))
    [{10 10 20 "x"}]
    [{10 10 20 "x"}]))

(deftest &-explain-pred
  (are [val expected]
    (= expected (-> (s/explain-data (s/& int? even?) val) ::s/problems first :pred))
    [] 'clojure.core/int?
    [0 2] '(clojure.spec.alpha/& clojure.core/int? clojure.core/even?)))

(deftest keys-explain-pred
  (is (= 'clojure.core/map? (-> (s/explain-data (s/keys :req [::x]) :a) ::s/problems first :pred))))

(deftest remove-def
  (is (= ::ABC (s/def ::ABC string?)))
  (is (= ::ABC (s/def ::ABC nil)))
  (is (nil? (s/get-spec ::ABC))))

;; TODO replace this with a generative test once we have specs for s/keys
(deftest map-spec-generators
  (s/def ::a nat-int?)
  (s/def ::b boolean?)
  (s/def ::c keyword?)
  (s/def ::d double?)
  (s/def ::e inst?)

  (is (= #{[::a]
           [::a ::b]
           [::a ::b ::c]
           [::a ::c]}
         (->> (s/exercise (s/keys :req [::a] :opt [::b ::c]) 100)
              (map (comp sort keys first))
              (into #{}))))

  (is (= #{[:a]
           [:a :b]
           [:a :b :c]
           [:a :c]}
         (->> (s/exercise (s/keys :req-un [::a] :opt-un [::b ::c]) 100)
              (map (comp sort keys first))
              (into #{}))))

  (is (= #{[::a ::b]
           [::a ::b ::c ::d]
           [::a ::b ::c ::d ::e]
           [::a ::b ::c ::e]
           [::a ::c ::d]
           [::a ::c ::d ::e]
           [::a ::c ::e]}
         (->> (s/exercise (s/keys :req [::a (or ::b (and ::c (or ::d ::e)))]) 200)
              (map (comp vec sort keys first))
              (into #{}))))

  (is (= #{[:a :b]
           [:a :b :c :d]
           [:a :b :c :d :e]
           [:a :b :c :e]
           [:a :c :d]
           [:a :c :d :e]
           [:a :c :e]}
         (->> (s/exercise (s/keys :req-un [::a (or ::b (and ::c (or ::d ::e)))]) 200)
              (map (comp vec sort keys first))
              (into #{})))))

(deftest tuple-explain-pred
  (are [val expected]
    (= expected (-> (s/explain-data (s/tuple int?) val) ::s/problems first :pred))
    :a 'clojure.core/vector?
    [] '(clojure.core/= (clojure.core/count %) 1)))

(comment
  (require '[clojure.test :refer (run-tests)])
  (in-ns 'clojure.test-clojure.spec)
  (run-tests)

  )
