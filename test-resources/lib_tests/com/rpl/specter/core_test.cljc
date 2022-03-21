(ns com.rpl.specter.core-test
  #?(:cljs (:require-macros
            [cljs.test :refer [is deftest]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [com.rpl.specter.cljs-test-helpers :refer [for-all+]]
            [com.rpl.specter.test-helpers :refer [ic-test]]
            [com.rpl.specter
              :refer [defprotocolpath defnav extend-protocolpath
                      nav declarepath providepath select select-one select-one!
                      select-first transform setval replace-in
                      select-any selected-any? collected? traverse
                      multi-transform path dynamicnav recursive-path
                      defdynamicnav traverse-all satisfies-protpath? end-fn
                      vtransform]]))
  (:use
    #?(:clj [clojure.test :only [deftest is]])
    #?(:clj [clojure.test.check.clojure-test :only [defspec]])
    #?(:clj [com.rpl.specter.test-helpers :only [for-all+ ic-test]])
    #?(:clj [com.rpl.specter
             :only [defprotocolpath defnav extend-protocolpath
                    nav declarepath providepath select select-one select-one!
                    select-first transform setval replace-in
                    select-any selected-any? collected? traverse
                    multi-transform path dynamicnav recursive-path
                    defdynamicnav traverse-all satisfies-protpath? end-fn
                    vtransform]]))



  (:require #?(:clj [clojure.test.check.generators :as gen])
            #?(:clj [clojure.test.check.properties :as prop])
            #?(:cljs [clojure.test.check :as tc])
            #?(:cljs [clojure.test.check.generators :as gen])
            #?(:cljs [clojure.test.check.properties :as prop :include-macros true])
            [com.rpl.specter :as s]
            [com.rpl.specter.transients :as t]
            [clojure.set :as set]))

;;TODO:
;; test walk, codewalk

(defn limit-size [n {gen :gen}]
  (gen/->Generator
   (fn [rnd _size]
     (gen rnd (if (< _size n) _size n)))))

(defn gen-map-with-keys [key-gen val-gen & keys]
  (gen/bind (gen/map key-gen val-gen)
            (fn [m]
              (gen/bind
               (apply gen/hash-map (mapcat (fn [k] [k val-gen]) keys))
               (fn [m2]
                 (gen/return (merge m m2)))))))

(defspec select-all-keyword-filter
  (for-all+
    [kw gen/keyword
     v (gen/vector (limit-size 5
                     (gen-map-with-keys gen/keyword gen/int kw)))
     pred (gen/elements [odd? even?])]
    (= (select [s/ALL kw pred] v)
       (->> v (map kw) (filter pred)))))


(defspec select-pos-extreme-pred
  (for-all+
   [v (gen/vector gen/int)
    pred (gen/elements [odd? even?])
    pos (gen/elements [[s/FIRST first] [s/LAST last]])]
   (= (select-one [(s/filterer pred) (first pos)] v)
      (->> v (filter pred) ((last pos))))))


(defspec select-all-on-map
  (for-all+
    [m (limit-size 5 (gen/map gen/keyword gen/int))
     p (gen/elements [s/MAP-VALS [s/ALL s/LAST]])]
    (= (select p m)
       (for [[k v] m] v))))


(deftest select-one-test
   (is (thrown? #?(:clj Exception :cljs js/Error) (select-one [s/ALL even?] [1 2 3 4])))
   (is (= 1 (select-one [s/ALL odd?] [2 4 1 6]))))


(deftest select-first-test
  (is (= 7 (select-first [(s/filterer odd?) s/ALL #(> % 4)] [3 4 2 3 7 5 9 8])))
  (is (nil? (select-first [s/ALL even?] [1 3 5 9]))))


(defspec transform-all-on-map
  (for-all+
    [m (limit-size 5 (gen/map gen/keyword gen/int))
     p (gen/elements [s/MAP-VALS [s/ALL s/LAST]])]
    (= (transform p inc m)
       (into {} (for [[k v] m] [k (inc v)])))))


(defspec transform-all
  (for-all+
   [v (gen/vector gen/int)]
   (let [v2 (transform [s/ALL] inc v)]
    (and (vector? v2) (= v2 (map inc v))))))


(defspec transform-all-list
  (for-all+
   [v (gen/list gen/int)]
   (let [v2 (transform [s/ALL] inc v)]
     (and (seq? v2) (= v2 (map inc v))))))


(defspec transform-all-filter
  (for-all+
   [v (gen/vector gen/int)
    pred (gen/elements [odd? even?])
    action (gen/elements [inc dec])]
   (let [v2 (transform [s/ALL pred] action v)]
     (= v2 (map (fn [v] (if (pred v) (action v) v)) v)))))


(defspec transform-last
  (for-all+
   [v (gen/not-empty (gen/vector gen/int))
    pred (gen/elements [inc dec])]
   (let [v2 (transform [s/LAST] pred v)]
     (= v2 (concat (butlast v) [(pred (last v))])))))


(defspec transform-first
  (for-all+
   [v (gen/not-empty (gen/vector gen/int))
    pred (gen/elements [inc dec])]
   (let [v2 (transform [s/FIRST] pred v)]
     (= v2 (concat [(pred (first v))] (rest v))))))


(defspec transform-filterer-all-equivalency
  (prop/for-all
   [s (gen/vector gen/int)
    target-type (gen/elements ['() []])
    pred (gen/elements [even? odd?])
    updater (gen/elements [inc dec])]
   (let [v (into target-type s)
         v2 (transform [(s/filterer pred) s/ALL] updater v)
         v3 (transform [s/ALL pred] updater v)]
     (and (= v2 v3) (= (type v2) (type v3))))))


(defspec transform-with-context
  (for-all+
    [kw1 gen/keyword
     kw2 gen/keyword
     m (limit-size 10 (gen-map-with-keys gen/keyword gen/int kw1 kw2))
     pred (gen/elements [odd? even?])]
    (= (transform [(s/collect-one kw2) kw1 pred] + m)
       (if (pred (kw1 m))
          (assoc m kw1 (+ (kw1 m) (kw2 m)))
          m))))


(defn differing-elements [v1 v2]
  (->> (map vector v1 v2)
       (map-indexed (fn [i [e1 e2]]
                      (if (not= e1 e2)
                        i)))
       (filter identity)))

(defspec transform-last-compound
  (for-all+
   [pred (gen/elements [odd? even?])
    v (gen/such-that #(some pred %) (gen/vector gen/int))]
   (let [v2 (transform [(s/filterer pred) s/LAST] inc v)
         differing-elems (differing-elements v v2)]
     (and (= (count v2) (count v))
          (= (count differing-elems) 1)
          (every? (complement pred) (drop (first differing-elems) v2))))))


;; max sizes prevent too much data from being generated and keeps test from taking forever
(defspec transform-keyword
  (for-all+
   [k1 (limit-size 3 gen/keyword)
    k2 (limit-size 3 gen/keyword)
    m1 (limit-size 5
                 (gen-map-with-keys
                  gen/keyword
                  (gen-map-with-keys gen/keyword gen/int k2)
                  k1))
    pred (gen/elements [inc dec])]
   (let [m2 (transform [k1 k2] pred m1)]
     (and (= (assoc-in m1 [k1 k2] nil) (assoc-in m2 [k1 k2] nil))
          (= (pred (get-in m1 [k1 k2])) (get-in m2 [k1 k2]))))))


(defspec replace-in-test
  (for-all+
    [v (gen/vector gen/int)]
    (let [res (->> v (map (fn [v] (if (even? v) (inc v) v))))
          user-ret (->> v
                        (filter even?)
                        (map (fn [v] [v v]))
                        (apply concat))
          user-ret (if (empty? user-ret) nil user-ret)]
      (= (replace-in [s/ALL even?] (fn [v] [(inc v) [v v]]) v)
         [res user-ret]))))


(defspec replace-in-custom-merge
  (for-all+
    [v (gen/vector gen/int)]
    (let [res (->> v (map (fn [v] (if (even? v) (inc v) v))))
          last-even (->> v (filter even?) last)
          user-ret (if last-even {:a last-even})]
      (= (replace-in [s/ALL even?] (fn [v] [(inc v) v]) v :merge-fn (fn [curr new]
                                                                        (assoc curr :a new)))
         [res user-ret]))))


(defspec srange-extremes-test
  (for-all+
   [v (gen/vector gen/int)
    v2 (gen/vector gen/int)]
   (let [b (setval s/BEGINNING v2 v)
         e (setval s/END v2 v)]
     (and (= b (concat v2 v))
          (= e (concat v v2))))))


(defspec srange-test
  (for-all+
   [v (gen/vector gen/int)
    b (gen/elements (-> v count inc range))
    e (gen/elements (range b (-> v count inc)))]

   (let [sv (subvec v b e)
         predcount (fn [pred v] (->> v (filter pred) count))
         even-count (partial predcount even?)
         odd-count (partial predcount odd?)
         b (transform (s/srange b e) (fn [r] (filter odd? r)) v)]
     (and (= (odd-count v) (odd-count b))
          (= (+ (even-count b) (even-count sv))
             (even-count v))))))


(deftest structure-path-directly-test
  (is (= 3 (select-one :b {:a 1 :b 3})))
  (is (= 5 (select-one (s/comp-paths :a :b) {:a {:b 5}}))))


(deftest atom-test
  (let [v (transform s/ATOM inc (atom 1))]
    (is (instance? #?(:clj clojure.lang.Atom :cljs cljs.core/Atom) v))
    (is (= 2 (select-one s/ATOM v) @v))))

(defspec view-test
  (for-all+
    [i gen/int
     afn (gen/elements [inc dec])]
    (= (first (select (s/view afn) i))
       (afn i)
       (transform (s/view afn) identity i))))


(defspec must-test
  (for-all+
    [k1 gen/int
     k2 (gen/such-that #(not= k1 %) gen/int)
     m (gen-map-with-keys gen/int gen/int k1)
     op (gen/elements [inc dec])]

    (let [m (dissoc m k2)]
      (and (= (transform (s/must k1) op m)
              (transform (s/keypath k1) op m))
           (= (transform (s/must k2) op m) m)
           (= (select (s/must k1) m) (select (s/keypath k1) m))
           (empty? (select (s/must k2) m))))))


(defspec parser-test
  (for-all+
    [i gen/int
     afn (gen/elements [inc dec #(* % 2)])
     bfn (gen/elements [inc dec #(* % 2)])
     cfn (gen/elements [inc dec #(* % 2)])]
    (and (= (select-one! (s/parser afn bfn) i)
            (afn i))
         (= (transform (s/parser afn bfn) cfn i)
            (-> i afn cfn bfn)))))


(deftest selected?-test
  (is (= [[1 3 5] [2 :a] [7 11 4 2 :a] [10 1 :a] []]
         (setval [s/ALL (s/selected? s/ALL even?) s/END]
                 [:a]
                 [[1 3 5] [2] [7 11 4 2] [10 1] []])))

  (is (= [2 4] (select [s/ALL (s/selected? even?)] [1 2 3 4])))
  (is (= [1 3] (select [s/ALL (s/not-selected? even?)] [1 2 3 4]))))


(defspec identity-test
  (for-all+
    [i gen/int
     afn (gen/elements [inc dec])]
    (and (= [i] (select nil i))
         (= (afn i) (transform nil afn i)))))

(deftest nil-comp-test
  (is (= [5] (select (com.rpl.specter.impl/comp-paths* nil) 5))))

(defspec putval-test
  (for-all+
   [kw gen/keyword
    m (limit-size 10 (gen-map-with-keys gen/keyword gen/int kw))
    c gen/int]
   (= (transform [(s/putval c) kw] + m)
      (transform [kw (s/putval c)] + m)
      (assoc m kw (+ c (get m kw))))))


(defspec empty-selector-test
  (for-all+
   [v (gen/vector gen/int)]
   (= [v]
      (select [] v)
      (select nil v)
      (select (s/comp-paths) v)
      (select (s/comp-paths nil) v)
      (select [nil nil nil] v))))


(defspec empty-selector-transform-test
  (for-all+
   [kw gen/keyword
    m (limit-size 10 (gen-map-with-keys gen/keyword gen/int kw))]
   (and (= m
           (transform nil identity m)
           (transform [] identity m)
           (transform (s/comp-paths []) identity m)
           (transform (s/comp-paths nil nil) identity m))

        (= (transform kw inc m)
           (transform [nil kw] inc m)
           (transform (s/comp-paths kw nil) inc m)
           (transform (s/comp-paths nil kw nil) inc m)))))


(deftest compose-empty-comp-path-test
  (let [m {:a 1}]
    (is (= [1]
           (select [:a (s/comp-paths)] m)
           (select [(s/comp-paths) :a] m)))))


(defspec mixed-selector-test
  (for-all+
   [k1 (limit-size 3 gen/keyword)
    k2 (limit-size 3 gen/keyword)
    m (limit-size 5
                (gen-map-with-keys
                 gen/keyword
                 (gen-map-with-keys gen/keyword gen/int k2)
                 k1))]
   (= [(-> m k1 k2)]
      (select [k1 (s/comp-paths k2)] m)
      (select [(s/comp-paths k1) k2] m)
      (select [(s/comp-paths k1 k2) nil] m)
      (select [(s/comp-paths) k1 k2] m)
      (select [k1 (s/comp-paths) k2] m))))


(deftest cond-path-test
  (is (= [4 2 6 8 10]
         (select [s/ALL (s/cond-path even? [(s/view inc) (s/view inc)]
                                 #(= 3 %) (s/view dec))]
                 [1 2 3 4 5 6 7 8])))
  (is (empty? (select (s/if-path odd? (s/view inc)) 2)))
  (is (= [6 2 10 6 14]
         (transform [(s/putval 2)
                     s/ALL
                     (s/if-path odd? [(s/view inc) (s/view inc)] (s/view dec))]
                    *
                    [1 2 3 4 5])))

  (is (= 2
         (transform [(s/putval 2)
                     (s/if-path odd? (s/view inc))]
                  *
                  2))))


(defspec cond-path-selector-test
  (for-all+
   [k1 (limit-size 3 gen/keyword)
    k2 (limit-size 3 gen/keyword)
    k3 (limit-size 3 gen/keyword)
    m (limit-size 5
                (gen-map-with-keys
                 gen/keyword
                 gen/int
                 k1
                 k2
                 k3))
    pred (gen/elements [odd? even?])]

   (let [v1 (get m k1)
         k (if (pred v1) k2 k3)]
     (and
       (= (transform (s/if-path [k1 pred] k2 k3) inc m)
          (transform k inc m))
       (= (select (s/if-path [k1 pred] k2 k3) m)
          (select k m))))))


(deftest optimized-if-path-test
  (is (= [-4 -2] (select [s/ALL (s/if-path [even? neg?] s/STAY)]
                   [1 2 -3 -4 0 -2])))
  (is (= [1 2 -3 4 0 2] (transform [s/ALL (s/if-path [even? neg?] s/STAY)]
                          -
                          [1 2 -3 -4 0 -2]))))


(defspec multi-path-test
  (for-all+
    [k1 (limit-size 3 gen/keyword)
     k2 (limit-size 3 gen/keyword)
     m (limit-size 5
                 (gen-map-with-keys
                  gen/keyword
                  gen/int
                  k1
                  k2))]

    (= (transform (s/multi-path k1 k2) inc m)
       (->> m
            (transform k1 inc)
            (transform k2 inc)))))


(deftest empty-pos-transform
  (is (empty? (select s/FIRST [])))
  (is (empty? (select s/LAST [])))
  (is (= [] (transform s/FIRST inc [])))
  (is (= [] (transform s/LAST inc []))))


(defspec set-filter-test
  (for-all+
    [k1 gen/keyword
     k2 (gen/such-that #(not= k1 %) gen/keyword)
     k3 (gen/such-that (complement #{k1 k2}) gen/keyword)
     v (gen/vector (gen/elements [k1 k2 k3]))]
    (= (filter #{k1 k2} v) (select [s/ALL #{k1 k2}] v))))


(deftest nil-select-one-test
  (is (= nil (select-one! s/ALL [nil])))
  (is (thrown? #?(:clj Exception :cljs js/Error) (select-one! s/ALL []))))



(defspec transformed-test
  (for-all+
    [v (gen/vector gen/int)
     pred (gen/elements [even? odd?])
     op   (gen/elements [inc dec])]
    (= (select-one (s/transformed [s/ALL pred] op) v)
       (transform [s/ALL pred] op v))))


(defspec basic-parameterized-composition-test
  (for-all+
    [k1 (limit-size 3 gen/keyword)
     k2 (limit-size 3 gen/keyword)
     m1 (limit-size 5
                 (gen-map-with-keys
                  gen/keyword
                  (gen-map-with-keys gen/keyword gen/int k2)
                  k1))
     pred (gen/elements [inc dec])]
    (let [p (dynamicnav [a b] (path (s/keypath a) (s/keypath b)))]
      (and
        (= (s/compiled-select (p k1 k2) m1) (select [k1 k2] m1))
        (= (s/compiled-transform (p k1 k2) pred m1) (transform [k1 k2] pred m1))))))


(defspec filterer-param-test
  (for-all+
    [k gen/keyword
     k2 gen/keyword
     v (gen/vector
         (limit-size 5
           (gen-map-with-keys
                        gen/keyword
                        gen/int
                        k
                        k2)))

     pred (gen/elements [odd? even?])
     updater (gen/elements [inc dec])]
    (and
      (= (select (s/filterer (s/keypath k) pred) v)
         (select (s/filterer k pred) v))
      (= (transform [(s/filterer (s/keypath k) pred) s/ALL k2]
           updater
           v)
         (transform [(s/filterer k pred) s/ALL k2]
           updater
           v)))))


(deftest nested-param-paths
  (let [p (fn [a b c]
            (path
              (s/filterer (s/keypath a)
                          (s/selected? s/ALL
                                       (s/keypath b)
                                       (s/filterer (s/keypath c) even?)
                                       s/ALL))))
        p2 (p :a :b :c)
        p3 (s/filterer :a (s/selected? s/ALL :b (s/filterer :c even?) s/ALL))
        data [{:a [{:b [{:c 4 :d 5}]}]}
              {:a [{:c 3}]}
              {:a [{:b [{:c 7}] :e [1]}]}]]

    (is (= (select p2 data)
           (select p3 data)
           [[{:a [{:b [{:c 4 :d 5}]}]}]]))))



(defspec subselect-nested-vectors
  (for-all+
    [v1 (gen/vector
         (gen/vector gen/int))]
    (let [path (s/comp-paths (s/subselect s/ALL s/ALL))
          v2 (s/compiled-transform path reverse v1)]
      (and
        (= (s/compiled-select path v1) [(flatten v1)])
        (= (flatten v1) (reverse (flatten v2)))
        (= (map count v1) (map count v2))))))

(defspec subselect-param-test
  (for-all+
    [k gen/keyword
     v (gen/vector
         (limit-size 5
           (gen-map-with-keys
             gen/keyword
             gen/int
             k)))]
    (and
     (= (s/compiled-select (s/subselect s/ALL (s/keypath k)) v)
        [(map k v)])
     (let [v2 (s/compiled-transform (s/comp-paths (s/subselect s/ALL (s/keypath k)))
                                    reverse
                                    v)]
       (and (= (map k v) (reverse (map k v2)))
            (= (map #(dissoc % k) v)
               (map #(dissoc % k) v2))))))) ; only key k was touched in any of the maps


(defspec param-multi-path-test
  (for-all+
    [k1 gen/keyword
     k2 gen/keyword
     k3 gen/keyword
     m (limit-size 5
         (gen-map-with-keys
           gen/keyword
           gen/int
           k1
           k2
           k3))

     pred1 (gen/elements [odd? even?])
     pred2 (gen/elements [odd? even?])
     updater (gen/elements [inc dec])]

    (let [paths [(path (s/multi-path [(s/keypath k1) pred1] [(s/keypath k2) pred2] k3))
                 (path (s/multi-path [k1 pred1] [(s/keypath k2) pred2] (s/keypath k3)))
                 (path (s/multi-path [(s/keypath k1) pred1] [(s/keypath k2) pred2] (s/keypath k3)))
                 (s/multi-path [k1 pred1] [k2 pred2] k3)
                 (path (s/multi-path [k1 pred1] [(s/keypath k2) pred2] k3))]]

     (and
       (apply =
         (for [p paths]
           (select p m)))

       (apply =
         (for [p paths]
           (transform p updater m)))))))


(defspec subset-test
  (for-all+
    [s1 (gen/vector (limit-size 5 gen/keyword))
     s2 (gen/vector (limit-size 5 gen/keyword))
     s3 (gen/vector (limit-size 5 gen/int))
     s4 (gen/vector (limit-size 5 gen/keyword))]
    (let [s1 (set s1)
          s2 (set s1)
          s3 (set s1)
          s4 (set s1)
          combined (set/union s1 s2)
          ss (set/union s2 s3)]
      (and
        (= (transform (s/subset s3) identity combined) combined)
        (= (setval (s/subset s3) #{} combined) (set/difference combined s2))
        (= (setval (s/subset s3) s4 combined) (-> combined (set/difference s2) (set/union s4)))))))


(deftest submap-test
  (is (= [{:foo 1}]
         (select [(s/submap [:foo :baz])] {:foo 1 :bar 2})))
  (is (= {:foo 1, :barry 1}
         (setval [(s/submap [:bar])] {:barry 1} {:foo 1 :bar 2})))
  (is (= {:bar 1, :foo 2}
         (transform [(s/submap [:foo :baz]) s/ALL s/LAST] inc {:foo 1 :bar 1})))
  (is (= {:a {:new 1}
          :c {:new 1
              :old 1}}
         (setval [s/ALL s/LAST (s/submap [])] {:new 1} {:a nil, :c {:old 1}}))))

(deftest nil->val-test
  (is (= {:a #{:b}}
         (setval [:a s/NIL->SET (s/subset #{})] #{:b} nil)))
  (is (= {:a #{:b :c :d}}
         (setval [:a s/NIL->SET (s/subset #{})] #{:b} {:a #{:c :d}})))
  (is (= {:a [:b]}
         (setval [:a s/NIL->VECTOR s/END] [:b] nil))))


(defspec void-test
  (for-all+
    [s1 (gen/vector (limit-size 5 gen/int))]
    (and
      (empty? (select s/STOP s1))
      (empty? (select [s/STOP s/ALL s/ALL s/ALL s/ALL] s1))
      (= s1 (transform s/STOP inc s1))
      (= s1 (transform [s/ALL s/STOP s/ALL] inc s1))
      (= (transform [s/ALL (s/cond-path even? nil odd? s/STOP)] inc s1)
         (transform [s/ALL even?] inc s1)))))


(deftest stay-continue-tests
  (is (= [[1 2 [:a :b]] [3 [:a :b]] [:a :b [:a :b]]]
         (setval [(s/stay-then-continue s/ALL) s/END] [[:a :b]] [[1 2] [3]])))
  (is (= [[1 2 [:a :b]] [3 [:a :b]] [:a :b]]
         (setval [(s/continue-then-stay s/ALL) s/END] [[:a :b]] [[1 2] [3]])))
  (is (= [[1 2 3] 1 3]
         (select (s/stay-then-continue s/ALL odd?) [1 2 3])))
  (is (= [1 3 [1 2 3]]
         (select (s/continue-then-stay s/ALL odd?) [1 2 3]))))



(declarepath MyWalker)

(providepath MyWalker
  (s/if-path vector?
    (s/if-path [s/FIRST #(= :abc %)]
      (s/continue-then-stay s/ALL MyWalker)
      [s/ALL MyWalker])))


(deftest recursive-path-test
  (is (= [9 1 10 3 1]
         (select [MyWalker s/ALL number?]
           [:bb [:aa 34 [:abc 10 [:ccc 9 8 [:abc 9 1]]]] [:abc 1 [:abc 3]]])))

  (is (= [:bb [:aa 34 [:abc 11 [:ccc 9 8 [:abc 10 2]]]] [:abc 2 [:abc 4]]]
         (transform [MyWalker s/ALL number?] inc
           [:bb [:aa 34 [:abc 10 [:ccc 9 8 [:abc 9 1]]]] [:abc 1 [:abc 3]]]))))


(def map-key-walker
  (recursive-path [akey] p
    [s/ALL
     (s/if-path [s/FIRST #(= % akey)]
       s/LAST
       [s/LAST p])]))

(deftest recursive-params-path-test
  (is (= #{1 2 3} (set (select (map-key-walker :aaa)
                               {:a {:aaa 3  :b {:c {:aaa 2} :aaa 1}}}))))
  (is (= {:a {:aaa 4 :b {:c {:aaa 3} :aaa 2}}}
         (transform (map-key-walker :aaa) inc
                      {:a {:aaa 3  :b {:c {:aaa 2} :aaa 1}}})))
  (is (= {:a {:c {:b "X"}}}
         (setval (map-key-walker :b) "X" {:a {:c {:b {:d 1}}}}))))


(deftest recursive-params-composable-path-test
  (let [p (fn [k k2] (path (s/keypath k) (map-key-walker k2)))]
    (is (= [1] (select (p 1 :a) [{:a 3} {:a 1} {:a 2}])))))


(deftest all-map-test
  (is (= {3 3} (transform [s/ALL s/FIRST] inc {2 3})))
  (is (= {3 21 4 31} (transform [s/ALL s/ALL] inc {2 20 3 30}))))



(def NestedHigherOrderWalker
  (recursive-path [k] p
    (s/if-path vector?
      (s/if-path [s/FIRST #(= % k)]
        (s/continue-then-stay s/ALL p)
        [s/ALL p]))))


(deftest nested-higher-order-walker-test
  (is (= [:q [:abc :I 3] [:ccc [:abc :I] [:abc :I "a" [:abc :I [:abc :I [:d]]]]]]
         (setval [(NestedHigherOrderWalker :abc) (s/srange 1 1)]
                 [:I]
                 [:q [:abc 3] [:ccc [:abc] [:abc "a" [:abc [:abc [:d]]]]]]))))


#?(:clj
   (deftest large-params-test
     (let [path (apply com.rpl.specter.impl/comp-navs (for [i (range 25)] (s/keypath i)))
           m (reduce
               (fn [m k]
                 {k m})
               :a
               (reverse (range 25)))]
       (is (= :a (select-one path m))))))

;;TODO: there's a bug in clojurescript that won't allow
;; non function implementations of IFn to have more than 20 arguments

#?(:clj
   (do
     (defprotocolpath AccountPath [])
     (defrecord Account [funds])
     (defrecord User [account])
     (defrecord Family [accounts])
     (extend-protocolpath AccountPath User :account Family [:accounts s/ALL])))


#?(:clj
   (deftest protocolpath-basic-test
     (let [data [(->User (->Account 30))
                 (->User (->Account 50))
                 (->Family [(->Account 51) (->Account 52)])]]
       (is (= [30 50 51 52]
              (select [s/ALL AccountPath :funds] data)))
       (is (= [(->User (->Account 31))
               (->User (->Account 51))
               (->Family [(->Account 52) (->Account 53)])]
              (transform [s/ALL AccountPath :funds]
                         inc
                         data))))))


#?(:clj
   (do
     (defprotocolpath LabeledAccountPath [label])
     (defrecord LabeledUser [account])
     (defrecord LabeledFamily [accounts])
     (extend-protocolpath LabeledAccountPath
       LabeledUser [:account (s/keypath label)]
       LabeledFamily [:accounts (s/keypath label) s/ALL])))


#?(:clj
   (deftest protocolpath-params-test
     (let [data [(->LabeledUser {:a (->Account 30)})
                 (->LabeledUser {:a (->Account 50)})
                 (->LabeledFamily {:a [(->Account 51) (->Account 52)]})]]
       (is (= [30 50 51 52]
              (select [s/ALL (LabeledAccountPath :a) :funds] data)))
       (is (= [(->LabeledUser {:a (->Account 31)})
               (->LabeledUser {:a (->Account 51)})
               (->LabeledFamily {:a [(->Account 52) (->Account 53)]})]
              (transform [s/ALL (LabeledAccountPath :a) :funds]
                         inc
                         data))))))



#?(:clj
   (do
     (defprotocolpath CustomWalker [])
     (extend-protocolpath CustomWalker
       Object nil
       clojure.lang.PersistentHashMap [(s/keypath :a) CustomWalker]
       clojure.lang.PersistentArrayMap [(s/keypath :a) CustomWalker]
       clojure.lang.PersistentVector [s/ALL CustomWalker])))


#?(:clj
   (deftest mixed-rich-regular-protocolpath
     (is (= [1 2 3 11 21 22 25]
            (select [CustomWalker number?] [{:a [1 2 :c [3]]} [[[[[[11]]] 21 [22 :c 25]]]]])))
     (is (= [2 3 [[[4]] :b 0] {:a 4 :b 10}]
            (transform [CustomWalker number?] inc [1 2 [[[3]] :b -1] {:a 3 :b 10}])))))



#?(
   :clj
   (defn make-queue [coll]
     (reduce
       #(conj %1 %2)
       clojure.lang.PersistentQueue/EMPTY
       coll))

   :cljs
   (defn make-queue [coll]
     (reduce
       #(conj %1 %2)
       #queue []
       coll)))


(defspec transform-idempotency 50
         (for-all+
           [v1 (gen/vector gen/int)
            l1 (gen/list gen/int)
            m1 (gen/map gen/keyword gen/int)]
           (let [s1 (set v1)
                 q1 (make-queue v1)
                 v2 (transform s/ALL identity v1)
                 m2 (transform s/ALL identity m1)
                 s2 (transform s/ALL identity s1)
                 l2 (transform s/ALL identity l1)
                 q2 (transform s/ALL identity q1)]
             (and
               (= v1 v2)
               (= (type v1) (type v2))
               (= m1 m2)
               (= (type m1) (type m2))
               (= s1 s2)
               (= (type s1) (type s2))
               (= l1 l2)
               (seq? l2) ; Transformed lists are only guaranteed to impelment ISeq
               (= q1 q2)
               (= (type q1) (type q2))))))

(defn ^:direct-nav double-str-keypath [s1 s2]
  (path (s/keypath (str s1 s2))))

(defn ^:direct-nav some-keypath
  ([] (s/keypath "a"))
  ([k1] (s/keypath (str k1 "!")))
  ([k & args] (s/keypath "bbb")))

(deftest nav-constructor-test
  ;; this also tests that the eval done by clj platform during inline
  ;; caching rebinds to the correct namespace since this is run
  ;; by clojure.test in a different namespace
  (is (= 1 (select-one! (double-str-keypath "a" "b") {"ab" 1 "c" 2})))
  (is (= 1 (select-one! (some-keypath) {"a" 1 "a!" 2 "bbb" 3 "d" 4})))
  (is (= 2 (select-one! (some-keypath "a") {"a" 1 "a!" 2 "bbb" 3 "d" 4})))
  (is (= 3 (select-one! (some-keypath 1 2 3 4 5) {"a" 1 "a!" 2 "bbb" 3 "d" 4}))))


(def ^:dynamic *APATH* s/keypath)

(deftest inline-caching-test
  (ic-test
    [k]
    [s/ALL (s/must k)]
    inc
    [{:a 1} {:b 2 :c 3} {:a 7 :d -1}]
    [[:a] [:b] [:c] [:d] [:e]])
  (ic-test
    []
    [s/ALL #{4 5 11} #(> % 2) (fn [e] (< e 7))]
    inc
    (range 20)
    [])
  (ic-test
    [v]
    (if v :a :b)
    inc
    {:a 1 :b 2}
    [[true] [false]])
  (ic-test
    [v]
    [s/ALL (double-str-keypath v (inc v))]
    str
    [{"12" :a "1011" :b} {"1011" :c}]
    [[1] [10]])
  (ic-test
    [k]
    (*APATH* k)
    str
    {:a 1 :b 2}
    [[:a] [:b] [:c]])

  (binding [*APATH* s/must]
    (ic-test
      [k]
      (*APATH* k)
      inc
      {:a 1 :b 2}
      [[:a] [:b] [:c]]))

  (ic-test
    [k k2]
    [s/ALL (s/selected? (s/must k) #(> % 2)) (s/must k2)]
    dec
    [{:a 1 :b 2} {:a 10 :b 6} {:c 7 :b 8} {:c 1 :d 9} {:c 3 :d -1}]
    [[:a :b] [:b :a] [:c :d] [:b :c]])

  (ic-test
    []
    [(s/transformed s/STAY inc)]
    inc
    10
    [])


  ;; verifying that these don't throw errors
  (is (= 1 (select-any (if true :a :b) {:a 1})))
  (is (= 3 (select-any (*APATH* :a) {:a 3})))
  (is (= 2 (select-any [:a (identity even?)] {:a 2})))

  (is (= [10 11] (select-one! [(s/putval 10) (s/transformed s/STAY #(inc %))] 10)))

  (is (= 2 (let [p :a] (select-one! [p even?] {:a 2}))))

  (is (= [{:a 2}] (let [p :a] (select [s/ALL (s/selected? p even?)] [{:a 2}])))))



(deftest nested-inline-caching-test
  (is (= [[1]]
         (let [a :b]
           (select
             (s/view
               (fn [v]
                 (select [(s/keypath v) (s/keypath a)]
                   {:a {:b 1}})))
             :a)))))



(defspec continuous-subseqs-filter-equivalence
  (for-all+
    [aseq (gen/vector (gen/elements [1 2 3 :a :b :c 4 5 :d :e]))
     pred (gen/elements [keyword? number?])]
    (= (setval (s/continuous-subseqs pred) nil aseq)
       (filter (complement pred) aseq))))


(deftest continuous-subseqs-test
  (is (= [1 "ab" 2 3 "c" 4 "def"]
         (transform
           (s/continuous-subseqs string?)
           (fn [s] [(apply str s)])
           [1 "a" "b" 2 3 "c" 4 "d" "e" "f"])))

  (is (= [[] [2] [4 6]]
         (select
           [(s/continuous-subseqs number?) (s/filterer even?)]
           [1 "a" "b" 2 3 "c" 4 5 6 "d" "e" "f"]))))



;; verifies that late binding of dynamic parameters works correctly
(deftest transformed-inline-caching
  (dotimes [i 10]
    (is (= [(inc i)] (select (s/transformed s/STAY #(+ % i)) 1)))))


;; test for issue #103
(deftest nil->val-regression-test
  (is (= false (transform (s/nil->val true) identity false)))
  (is (= false (select-one! (s/nil->val true) false))))


#?(:clj
   (deftest all-map-entry
     (let [e (transform s/ALL inc (first {1 3}))]
       (is (instance? clojure.lang.MapEntry e))
       (is (= 2 (key e)))
       (is (= 4 (val e))))))


(deftest select-on-empty-vector
  (is (= s/NONE (select-any s/ALL [])))
  (is (nil? (select-first s/ALL [])))
  (is (nil? (select-one s/ALL [])))
  (is (= s/NONE (select-any s/FIRST [])))
  (is (= s/NONE (select-any s/LAST [])))
  (is (nil? (select-first s/FIRST [])))
  (is (nil? (select-one s/FIRST [])))
  (is (nil? (select-first s/LAST [])))
  (is (nil? (select-one s/LAST []))))


(defspec select-first-one-any-equivalency
  (for-all+
    [aval gen/int
     apred (gen/elements [even? odd?])]
    (let [data [aval]
          r1 (select-any [s/ALL (s/pred apred)] data)
          r2 (select-first [s/ALL (s/pred apred)] data)
          r3 (select-one [s/ALL (s/pred apred)] data)
          r4 (first (select [s/ALL (s/pred apred)] data))
          r5 (select-any [s/FIRST (s/pred apred)] data)
          r6 (select-any [s/LAST (s/pred apred)] data)]

      (or (and (= r1 s/NONE) (nil? r2) (nil? r3) (nil? r4)
               (= r5 s/NONE) (= r6 s/NONE))
          (and (not= r1 s/NONE) (some? r1) (= r1 r2 r3 r4 r5 r6))))))


(deftest select-any-static-fn
  (is (= 2 (select-any even? 2)))
  (is (= s/NONE (select-any odd? 2))))


(deftest select-any-keywords
  (is (= s/NONE (select-any [:a even?] {:a 1})))
  (is (= 2 (select-any [:a even?] {:a 2})))
  (is (= s/NONE (select-any [(s/keypath "a") even?] {"a" 1})))
  (is (= 2 (select-any [(s/keypath "a") even?] {"a" 2})))
  (is (= s/NONE (select-any (s/must :b) {:a 1 :c 3})))
  (is (= 2 (select-any (s/must :b) {:a 1 :b 2 :c 3})))
  (is (= s/NONE (select-any [(s/must :b) odd?] {:a 1 :b 2 :c 3}))))


(defspec select-any-ALL
  (for-all+
    [v (gen/vector gen/int)
     pred (gen/elements [even? odd?])]
    (let [r1 (select [s/ALL pred] v)
          r2 (select-any [s/ALL pred] v)]
      (or (and (empty? r1) (= s/NONE r2))
          (contains? (set r1) r2)))))


(deftest select-any-beginning-end
  (is (= [] (select-any s/BEGINNING [1 2 3]) (select-any s/END [1])))
  (is (= s/NONE (select-any [s/BEGINNING s/STOP] [1 2 3]) (select-any [s/END s/STOP] [2 3]))))


(deftest select-any-walker
  (let [data [1 [2 3 4] [[6]]]]
    (is (= s/NONE (select-any (s/walker keyword?) data)))
    (is (= s/NONE (select-any [(s/walker number?) neg?] data)))
    (is (#{1 3} (select-any [(s/walker number?) odd?] data)))
    (is (#{2 4 6} (select-any [(s/walker number?) even?] data)))))


(defspec selected-any?-select-equivalence
  (for-all+
    [v (gen/vector gen/int)
     pred (gen/elements [even? odd?])]
    (let [r1 (not (empty? (select [s/ALL pred] v)))
          r2 (selected-any? [s/ALL pred] v)]
      (= r1 r2))))


(defn div-by-3? [v]
  (= 0 (mod v 3)))

(defspec selected?-filter-equivalence
  (for-all+
    [v (gen/vector gen/int)
     pred (gen/elements [even? odd?])]
    (and
      (= (select-any [s/ALL pred] v)
         (select-any [s/ALL (s/selected? pred)] v))

      (= (select-any [s/ALL pred div-by-3?] v)
         (select-any [s/ALL (s/selected? pred) div-by-3?] v)))))



(deftest multi-path-select-any-test
  (is (= s/NONE (select-any (s/multi-path s/STOP s/STOP) 1)))
  (is (= 1 (select-any (s/multi-path s/STAY s/STOP) 1)
           (select-any (s/multi-path s/STOP s/STAY) 1)
           (select-any (s/multi-path s/STOP s/STAY s/STOP) 1)))

  (is (= s/NONE (select-any [(s/multi-path s/STOP s/STAY) even?] 1))))


(deftest if-path-select-any-test
  (is (= s/NONE (select-any (s/if-path even? s/STAY) 1)))
  (is (= 2 (select-any (s/if-path even? s/STAY s/STAY) 2)))
  (is (= s/NONE (select-any [(s/if-path even? s/STAY s/STAY) odd?] 2)))
  (is (= 2 (select-any (s/if-path odd? s/STOP s/STAY) 2)))
  (is (= s/NONE (select-any [(s/if-path odd? s/STOP s/STAY) odd?] 2))))


(defspec transient-vector-test
  (for-all+
    [v (gen/vector (limit-size 5 gen/int))]
    (every? identity
            (for [[path transient-path f]
                  [[s/FIRST t/FIRST! (fnil inc 0)]  ;; fnil in case vector is empty
                   [s/LAST t/LAST! (fnil inc 0)]
                   [(s/keypath 0) (t/keypath! 0) (fnil inc 0)]
                   [s/END t/END! #(conj % 1 2 3)]]]
              (and (= (s/transform* path f v)
                      (persistent! (s/transform* transient-path f (transient v))))
                   (= (s/select* path v)
                      (s/select* transient-path (transient v))))))))

(defspec transient-map-test
  (for-all+
    [m (limit-size 5 (gen/not-empty (gen/map gen/keyword gen/int)))
     new-key gen/keyword]
    (let [existing-key (first (keys m))]
      (every? identity
              (for [[path transient-path f]
                    [[(s/keypath existing-key) (t/keypath! existing-key) inc]
                     [(s/keypath new-key) (t/keypath! new-key) (constantly 3)]
                     [(s/submap [existing-key new-key])
                      (t/submap! [existing-key new-key])
                      (constantly {new-key 1234})]]]
                (and (= (s/transform* path f m)
                        (persistent! (s/transform* transient-path f (transient m))))
                     (= (s/select* path m)
                        (s/select* transient-path (transient m)))))))))

(defspec meta-test
  (for-all+
    [v (gen/vector gen/int)
     meta-map (limit-size 5 (gen/map gen/keyword gen/int))]
    (= meta-map
       (meta (setval s/META meta-map v))
       (first (select s/META (with-meta v meta-map)))
       (first (select s/META (setval s/META meta-map v))))))


(deftest beginning-end-all-first-last-on-nil
  (is (= [2 3] (setval s/END [2 3] nil) (setval s/BEGINNING [2 3] nil)))
  (is (nil? (setval s/FIRST :a nil)))
  (is (nil? (setval s/LAST :a nil)))
  (is (nil? (transform s/ALL inc nil)))
  (is (empty? (select s/FIRST nil)))
  (is (empty? (select s/LAST nil)))
  (is (empty? (select s/ALL nil))))


(deftest map-vals-nil
  (is (= nil (transform s/MAP-VALS inc nil)))
  (is (empty? (select s/MAP-VALS nil))))


(defspec dispense-test
  (for-all+
    [k1 gen/int
     k2 gen/int
     k3 gen/int
     m (gen-map-with-keys gen/int gen/int k1 k2 k3)]
    (= (select [(s/collect-one (s/keypath k1))
                (s/collect-one (s/keypath k2))
                s/DISPENSE
                (s/collect-one (s/keypath k2))
                (s/keypath k3)]
               m)
       (select [(s/collect-one (s/keypath k2))
                (s/keypath k3)]
               m))))


(deftest collected?-test
  (let [data {:active-id 1 :items [{:id 1 :name "a"} {:id 2 :name "b"}]}]
    (is (= {:id 1 :name "a"}
           (select-any [(s/collect-one :active-id)
                        :items
                        s/ALL
                        (s/collect-one :id)
                        (collected? [a i] (= a i))
                        s/DISPENSE]

                       data)
           (select-any [(s/collect-one :active-id)
                        :items
                        s/ALL
                        (s/collect-one :id)
                        (collected? v (apply = v))
                        s/DISPENSE]

                       data))))

  (let [data {:active 3 :items [{:id 1 :val 0} {:id 3 :val 11}]}]
    (is (= (transform [:items s/ALL (s/selected? :id #(= % 3)) :val] inc data)
           (transform [(s/collect-one :active)
                       :items
                       s/ALL
                       (s/collect-one :id)
                       (collected? [a i] (= a i))
                       s/DISPENSE
                       :val]
                      inc
                      data)))))


(defspec traverse-test
  (for-all+
    [v (gen/vector gen/int)
     p (gen/elements [odd? even?])
     i gen/int]
    (and
      (= (reduce + (traverse [s/ALL p] v))
         (reduce + (filter p v)))
      (= (reduce + i (traverse [s/ALL p] v))
         (reduce + i (filter p v))))))

(def KeyAccumWalker
  (recursive-path [k] p
    (s/if-path (s/must k)
      s/STAY
      [s/ALL (s/collect-one s/FIRST) s/LAST p])))


(deftest recursive-if-path-select-vals-test
  (let [data {"e1" {"e2" {"e1" {:template 1} "e2" {:template 2}}}}]
    (is (= [["e1" "e2" "e1" {:template 1}] ["e1" "e2" "e2" {:template 2}]]
           (select (KeyAccumWalker :template) data)))
    (is (= {"e1" {"e2" {"e1" "e1e2e1" "e2" "e1e2e2"}}}
           (transform (KeyAccumWalker :template)
             (fn [& all] (apply str (butlast all)))
             data)))))


(deftest multi-path-vals-test
  (is (= {:a 1 :b 6 :c 3}
         (transform [(s/multi-path (s/collect-one :a) (s/collect-one :c)) :b]
           +
           {:a 1 :b 2 :c 3})))
  (is (= [[1 2] [3 2]]
         (select [(s/multi-path (s/collect-one :a) (s/collect-one :c)) :b]
           {:a 1 :b 2 :c 3}))))


(deftest sorted-map-by-transform
  (let [amap (sorted-map-by > 1 10 2 20 3 30)]
    (is (= [3 2 1] (keys (transform s/MAP-VALS inc amap))))
    (is (= [3 2 1] (keys (transform [s/ALL s/LAST] inc amap))))))


(deftest setval-vals-collection-test
  (is (= 2 (setval s/VAL 2 :a))))

(defspec multi-transform-test
  (for-all+
    [kw1 gen/keyword
     kw2 gen/keyword
     m (limit-size 5 (gen-map-with-keys gen/keyword gen/int kw1 kw2))]
    (= (->> m (transform [(s/keypath kw1) s/VAL] +) (transform (s/keypath kw2) dec))
       (multi-transform
         (s/multi-path [(s/keypath kw1) s/VAL (s/terminal +)]
                       [(s/keypath kw2) (s/terminal dec)])
         m))))


(deftest multi-transform-overrun-error
  (is (thrown? #?(:clj Exception :cljs js/Error) (multi-transform s/STAY 3))))


(deftest terminal-val-test
  (is (= 3 (multi-transform (s/terminal-val 3) 2)))
  (is (= 3 (multi-transform [s/VAL (s/terminal-val 3)] 2))))



(deftest multi-path-order-test
  (is (= 102
         (multi-transform
           (s/multi-path
            [odd? (s/terminal #(* 2 %))]
            [even? (s/terminal-val 100)]
            [#(= 100 %) (s/terminal inc)]
            [#(= 101 %) (s/terminal inc)])
           1))))


(defdynamicnav ignorer [a]
  s/STAY)

(deftest dynamic-nav-ignores-dynamic-arg
  (let [a 1]
    (is (= 1 (select-any (ignorer a) 1)))
    (is (= 1 (select-any (ignorer :a) 1)))))


(deftest nested-dynamic-nav
  (let [data {:a {:a 1 :b 2} :b {:a 3 :b 4}}
        afn (fn [a b] (select-any (s/selected? (s/must a)
                                               (s/selected? (s/must b)))
                                  data))]
    (is (= data (afn :a :a)))
    (is (= s/NONE (afn :a :c)))
    (is (= data (afn :a :b)))
    (is (= s/NONE (afn :c :a)))
    (is (= data (afn :b :a)))
    (is (= data (afn :b :b)))))

(deftest duplicate-map-keys-test
  (let [res (setval [s/ALL s/FIRST] "a" {:a 1 :b 2})]
    (is (= {"a" 2} res))
    (is (= 1 (count res)))))

(deftest inline-caching-vector-params-test
  (is (= [10 [11]] (multi-transform (s/terminal-val [10 [11]]) :a))))

(defn eachnav-fn-test [akey data]
  (select-any (s/keypath "a" akey) data))

(deftest eachnav-test
  (let [data {"a" {"b" 1 "c" 2}}]
    (is (= 1 (eachnav-fn-test "b" data)))
    (is (= 2 (eachnav-fn-test "c" data)))
    ))

(deftest traversed-test
  (is (= 10 (select-any (s/traversed s/ALL +) [1 2 3 4]))))

(defn- predand= [pred v1 v2]
  (and (pred v1)
       (pred v2)
       (= v1 v2)))

(defn listlike? [v]
  (or (list? v) (seq? v)))

(deftest nthpath-test
  (is (predand= vector? [1 2 -3 4] (transform (s/nthpath 2) - [1 2 3 4])))
  (is (predand= vector? [1 2 4] (setval (s/nthpath 2) s/NONE [1 2 3 4])))
  (is (predand= (complement vector?) '(1 -2 3 4) (transform (s/nthpath 1) - '(1 2 3 4))))
  (is (predand= (complement vector?) '(1 2 4) (setval (s/nthpath 2) s/NONE '(1 2 3 4))))
  (is (= [0 1 [2 4 4]] (transform (s/nthpath 2 1) inc [0 1 [2 3 4]])))
  )

(deftest remove-with-NONE-test
  (is (predand= vector? [1 2 3] (setval [s/ALL nil?] s/NONE [1 2 nil 3 nil])))
  (is (predand= listlike? '(1 2 3) (setval [s/ALL nil?] s/NONE '(1 2 nil 3 nil))))
  (is (= {:b 2} (setval :a s/NONE {:a 1 :b 2})))
  (is (= {:b 2} (setval (s/must :a) s/NONE {:a 1 :b 2})))
  (is (predand= vector? [1 3] (setval (s/keypath 1) s/NONE [1 2 3])))
  ;; test with PersistentArrayMap
  (is (= {:a 1 :c 3} (setval [s/MAP-VALS even?] s/NONE {:a 1 :b 2 :c 3 :d 4})))
  (is (= {:a 1 :c 3} (setval [s/ALL (s/selected? s/LAST even?)] s/NONE {:a 1 :b 2 :c 3 :d 4})))
  ;; test with PersistentHashMap
  (let [m (into {} (for [i (range 500)] [i i]))]
    (is (= (dissoc m 31) (setval [s/MAP-VALS #(= 31 %)] s/NONE m)))
    (is (= (dissoc m 31) (setval [s/ALL (s/selected? s/LAST #(= 31 %))] s/NONE m)))
    ))

(deftest fresh-collected-test
  (let [data [{:a 1 :b 2} {:a 3 :b 3}]]
    (is (= [[{:a 1 :b 2} 2]]
           (select [s/ALL
                    s/VAL
                    (s/with-fresh-collected
                      (s/collect-one :a)
                      (s/collected? [a] (= a 1)))
                    :b]
                   data)))
    (is (= [{:a 1 :b 3} {:a 3 :b 3}]
          (transform [s/ALL
                      s/VAL
                      (s/with-fresh-collected
                       (s/collect-one :a)
                       (s/collected? [a] (= a 1)))
                      :b]
            (fn [m v] (+ (:a m) v))
            data
            )))
    ))

(deftest traverse-all-test
  (is (= 3
         (transduce (comp (mapcat identity)
                          (traverse-all :a))
            (completing (fn [r i] (if (= i 4) (reduced r) (+ r i))))
            0
            [[{:a 1}] [{:a 2}] [{:a 4}] [{:a 5}]])))
  (is (= 6
         (transduce (traverse-all [s/ALL :a])
           +
           0
           [[{:a 1} {:a 2}] [{:a 3}]]
           )))
  (is (= [1 2]
         (into [] (traverse-all :a) [{:a 1} {:a 2}])))
  )

(deftest early-terminate-traverse-test
  (is (= 6
         (reduce
          (completing (fn [r i] (if (> r 5) (reduced r) (+ r i))))
          0
          (traverse [s/ALL s/ALL]
            [[1 2] [3 4] [5]])))))

(deftest select-any-vals-test
  (is (= [1 1] (select-any s/VAL 1))))

(deftest conditional-vals-test
  (is (= 2 (select-any (s/with-fresh-collected
                         (s/collect-one (s/keypath 0))
                         (s/if-path (collected? [n] (even? n))
                           (s/keypath 1)
                           (s/keypath 2)))
                       [4 2 3])))
  (is (= [4 2 3]
         (select-any (s/with-fresh-collected
                       (s/collect-one (s/keypath 0))
                       (s/selected? (collected? [n] (even? n))))
                     [4 2 3])))
  )

(deftest name-namespace-test
  (is (= :a (setval s/NAME "a" :e)))
  (is (= :a/b (setval s/NAME "b" :a/e)))
  (is (= 'a (setval s/NAME "a" 'e)))
  (is (= 'a/b (setval s/NAME "b" 'a/e)))
  (is (= :a/e (setval s/NAMESPACE "a" :e)))
  (is (= :a/e (setval s/NAMESPACE "a" :f/e)))
  (is (= 'a/e (setval s/NAMESPACE "a" 'e)))
  (is (= 'a/e (setval s/NAMESPACE "a" 'f/e)))
  )

(deftest string-navigation-test
  (is (= "ad" (setval (s/srange 1 3) "" "abcd")))
  (is (= "abcxd" (setval [(s/srange 1 3) s/END] "x" "abcd")))
  (is (= "bc" (select-any (s/srange 1 3) "abcd")))
  (is (= "ab" (setval s/END "b" "a")))
  (is (= "ba" (setval s/BEGINNING "b" "a")))
  (is (= "" (select-any s/BEGINNING "abc")))
  (is (= "" (select-any s/END "abc")))
  (is (= \a (select-any s/FIRST "abc")))
  (is (= \c (select-any s/LAST "abc")))
  (is (= "qbc" (setval s/FIRST \q "abc")))
  (is (= "abq" (setval s/LAST "q" "abc")))
  )

(deftest regex-navigation-test
  ;; also test regexes as implicit navs
  (is (= (select #"t" "test") ["t" "t"]))
  (is (= (select [:a (s/regex-nav #"t")] {:a "test"}) ["t" "t"]))
  (is (= (transform (s/regex-nav #"t") clojure.string/capitalize "test") "TesT"))
  ;; also test regexes as implicit navs
  (is (= (transform [:a #"t"] clojure.string/capitalize {:a "test"}) {:a "TesT"}))
  (is (= (transform (s/regex-nav #"\s+\w") clojure.string/triml "Hello      World!") "HelloWorld!"))
  (is (= (setval (s/regex-nav #"t") "z" "test") "zesz"))
  (is (= (setval [:a (s/regex-nav #"t")] "z" {:a "test"}) {:a "zesz"}))
  (is (= (transform (s/regex-nav #"aa*") (fn [s] (-> s count str)) "aadt") "2dt"))
  (is (= (transform (s/regex-nav #"[Aa]+") (fn [s] (apply str (take (count s) (repeat "@")))) "Amsterdam Aardvarks") "@msterd@m @@rdv@rks"))
  (is (= (select [(s/regex-nav #"(\S+):\ (\d+)") (s/nthpath 2)] "Mary: 1st George: 2nd Arthur: 3rd") ["1" "2" "3"]))
  (is (= (transform (s/subselect (s/regex-nav #"\d\w+")) reverse "Mary: 1st George: 2nd Arthur: 3rd") "Mary: 3rd George: 2nd Arthur: 1st"))
  )

(deftest single-value-none-navigators-test
  (is (predand= vector? [1 2 3] (setval s/AFTER-ELEM 3 [1 2])))
  (is (predand= listlike? '(1 2 3) (setval s/AFTER-ELEM 3 '(1 2))))
  (is (predand= listlike? '(1) (setval s/AFTER-ELEM 1 nil)))
  (is (predand= vector? [3 1 2] (setval s/BEFORE-ELEM 3 [1 2])))
  (is (predand= listlike? '(3 1 2) (setval s/BEFORE-ELEM 3 '(1 2))))
  (is (predand= listlike? '(1) (setval s/BEFORE-ELEM 1 nil)))
  (is (= #{1 2 3} (setval s/NONE-ELEM 3 #{1 2})))
  (is (= #{1} (setval s/NONE-ELEM 1 nil)))
  )

(deftest subvec-test
  (let [v (subvec [1] 0)]
    (is (predand= vector? [2] (transform s/FIRST inc v)))
    (is (predand= vector? [2] (transform s/LAST inc v)))
    (is (predand= vector? [2] (transform s/ALL inc v)))
    (is (predand= vector? [0 1] (setval s/BEGINNING [0] v)))
    (is (predand= vector? [1 0] (setval s/END [0] v)))
    (is (predand= vector? [0 1] (setval s/BEFORE-ELEM 0 v)))
    (is (predand= vector? [1 0] (setval s/AFTER-ELEM 0 v)))
    (is (predand= vector? [1 0] (setval (s/srange 1 1) [0] v)))
    ))

(defspec map-keys-all-first-equivalence-transform
  (for-all+
   [m (limit-size 10 (gen/map gen/int gen/keyword))]
   (= (transform s/MAP-KEYS inc m)
      (transform [s/ALL s/FIRST] inc m )
      )))

(defspec map-keys-all-first-equivalence-select
  (for-all+
    [m (limit-size 10 (gen/map gen/int gen/keyword))]
    (= (select s/MAP-KEYS m)
       (select [s/ALL s/FIRST] m)
       )))

(defspec remove-first-vector
  (for-all+
    [v (limit-size 10 (gen/not-empty (gen/vector gen/int)))]
    (let [newv (setval s/FIRST s/NONE v)]
      (and (= newv (vec (rest v)))
           (vector? newv)
           ))))

(defspec remove-first-list
  (for-all+
    [l (limit-size 10 (gen/not-empty (gen/list gen/int)))]
    (let [newl (setval s/FIRST s/NONE l)]
      (and (= newl (rest l))
           (listlike? newl)
           ))))

(defspec remove-last-vector
  (for-all+
    [v (limit-size 10 (gen/not-empty (gen/vector gen/int)))]
    (let [newv (setval s/LAST s/NONE v)]
      (and (= newv (vec (butlast v)))
           (vector? newv)
           ))))

(defspec remove-last-list
  (for-all+
    [l (limit-size 10 (gen/not-empty (gen/list gen/int)))]
    (let [newl (setval s/LAST s/NONE l)
          bl (butlast l)]
      (and (or (= newl bl) (and (nil? bl) (= '() newl)))
           (seq? newl)
           ))))

(deftest remove-extreme-string
  (is (= "b" (setval s/FIRST s/NONE "ab")))
  (is (= "a" (setval s/LAST s/NONE "ab")))
  )

(deftest nested-dynamic-arg-test
  (let [foo (fn [v] (multi-transform (s/terminal-val [v]) nil))]
    (is (= [1] (foo 1)))
    (is (= [10] (foo 10)))
    ))

(deftest filterer-remove-test
  (is (= [1 :a 3 5] (setval (s/filterer even?) [:a] [1 2 3 4 5])))
  (is (= [1 3 5] (setval (s/filterer even?) [] [1 2 3 4 5])))
  (is (= [1 3 5] (setval (s/filterer even?) nil [1 2 3 4 5])))
  )

(deftest helper-preds-test
  (let [data [1 2 2 3 4 0]]
    (is (= [2 2] (select [s/ALL (s/pred= 2)] data)))
    (is (= [1 2 2 0] (select [s/ALL (s/pred< 3)] data)))
    (is (= [1 2 2 3 0] (select [s/ALL (s/pred<= 3)] data)))
    (is (= [4] (select [s/ALL (s/pred> 3)] data)))
    (is (= [3 4] (select [s/ALL (s/pred>= 3)] data)))
    ))

(deftest map-key-test
  (is (= {:c 3} (setval (s/map-key :a) :b {:c 3})))
  (is (= {:b 2} (setval (s/map-key :a) :b {:a 2})))
  (is (= {:b 2} (setval (s/map-key :a) :b {:a 2 :b 1})))
  (is (= {:b 2} (setval (s/map-key :a) s/NONE {:a 1 :b 2})))
  )

(deftest set-elem-test
  (is (= #{:b :d} (setval (s/set-elem :a) :x #{:b :d})))
  (is (= #{:x :a} (setval (s/set-elem :b) :x #{:b :a})))
  (is (= #{:a} (setval (s/set-elem :b) :a #{:b :a})))
  (is (= #{:b} (setval (s/set-elem :a) s/NONE #{:a :b})))
  )

;; this function necessary to trigger the bug from happening
(defn inc2 [v] (inc v))
(deftest dynamic-function-arg-test
  (is (= {[2] 4} (let [a 1] (transform (s/keypath [(inc2 a)]) inc {[2] 3}))))
  )

(defrecord FooW [a b])

(deftest walker-test
  (is (= [1 2 3 4 5 6] (select (s/walker number?) [{1 2 :b '(3 :c 4)} 5 #{6 :d}])))
  (is (= [{:b '(:c)} #{:d}] (setval (s/walker number?) s/NONE [{:q 3 10 :l 1 2 :b '(3 :c 4)} 5 #{6 :d}])))
  (is (= [{:q 4 11 :l 2 3 :b '(4 :c 5)} 6 #{7 :d}]
         (transform (s/walker number?) inc [{:q 3 10 :l 1 2 :b '(3 :c 4)} 5 #{6 :d}])))
  (let [f (->FooW 1 2)]
    (is (= [[:a 1] [:b 2]] (select (s/walker (complement record?)) f)))
    (is (= (assoc f :a! 1 :b! 2) (setval [(s/walker (complement record?)) s/FIRST s/NAME s/END] "!" f)))
    (is (= (assoc f :b 1 :c 2) (transform [(s/walker (complement record?)) s/FIRST] (fn [k] (if (= :a k) :b :c)) f)))
    ))

(def MIDDLE
  (s/comp-paths
    (s/srange-dynamic
      (fn [aseq] (long (/ (count aseq) 2)))
      (end-fn [aseq s] (if (empty? aseq) 0 (inc s))))
    s/FIRST
    ))

(deftest srange-dynamic-test
  (is (= 2 (select-any MIDDLE [1 2 3])))
  (is (identical? s/NONE (select-any MIDDLE [])))
  (is (= 1 (select-any MIDDLE [1])))
  (is (= 2 (select-any MIDDLE [1 2])))
  (is (= [1 3 3] (transform MIDDLE inc [1 2 3])))
  )

(def ^:dynamic *dvar* :a)

(defn dvar-tester []
  (select-any *dvar* {:a 1 :b 2}))

(deftest dynamic-var-ic-test
  (is (= 1 (dvar-tester)))
  (is (= 2 (binding [*dvar* :b] (dvar-tester))))
  )

(deftest before-index-test
  (let [data [1 2 3]
        datal '(1 2 3)
        data-str "abcdef"]
    (is (predand= vector? [:a 1 2 3] (setval (s/before-index 0) :a data)))
    (is (predand= vector? [1 2 3] (setval (s/before-index 1) s/NONE data)))
    (is (predand= vector? [1 :a 2 3] (setval (s/before-index 1) :a data)))
    (is (predand= vector? [1 2 3 :a] (setval (s/before-index 3) :a data)))
    ; ensure inserting at index 0 in nil structure works, as in previous impl
    (is (predand= listlike? '(:a) (setval (s/before-index 0) :a nil)))
    (is (predand= listlike? '(:a 1 2 3) (setval (s/before-index 0) :a datal)))
    (is (predand= listlike? '(1 :a 2 3) (setval (s/before-index 1) :a datal)))
    (is (predand= listlike? '(1 2 3 :a) (setval (s/before-index 3) :a datal)))
    (is (predand= string? "abcxdef" (setval (s/before-index 3) (char \x) data-str)))
    ))

(deftest index-nav-test
  (let [data [1 2 3 4 5 6]
        datal '(1 2 3 4 5 6)]
    (is (predand= vector? [3 1 2 4 5 6] (setval (s/index-nav 2) 0 data)))
    (is (predand= vector? [1 3 2 4 5 6] (setval (s/index-nav 2) 1 data)))
    (is (predand= vector? [1 2 3 4 5 6] (setval (s/index-nav 2) 2 data)))
    (is (predand= vector? [1 2 4 5 3 6] (setval (s/index-nav 2) 4 data)))
    (is (predand= vector? [1 2 4 5 6 3] (setval (s/index-nav 2) 5 data)))
    (is (predand= vector? [6 1 2 3 4 5] (setval (s/index-nav 5) 0 data)))

    (is (predand= listlike? '(3 1 2 4 5 6) (setval (s/index-nav 2) 0 datal)))
    (is (predand= listlike? '(1 3 2 4 5 6) (setval (s/index-nav 2) 1 datal)))
    (is (predand= listlike? '(1 2 3 4 5 6) (setval (s/index-nav 2) 2 datal)))
    (is (predand= listlike? '(1 2 4 5 3 6) (setval (s/index-nav 2) 4 datal)))
    (is (predand= listlike? '(1 2 4 5 6 3) (setval (s/index-nav 2) 5 datal)))
    (is (predand= listlike? '(6 1 2 3 4 5) (setval (s/index-nav 5) 0 datal)))
    ))

(deftest indexed-vals-test
  (let [data [:a :b :c :d :e]]
    (is (= [[0 :a] [1 :b] [2 :c] [3 :d] [4 :e]] (select s/INDEXED-VALS data)))
    (is (= [:e :d :c :b :a] (setval [s/INDEXED-VALS s/FIRST] 0 data)))
    (is (= [:a :b :e :d :c] (setval [s/INDEXED-VALS s/FIRST] 2 data)))
    (is (= [:b :a :d :c :e] (transform [s/INDEXED-VALS s/FIRST odd?] dec data)))
    (is (= [:a :b :c :d :e] (transform [s/INDEXED-VALS s/FIRST odd?] inc data)))
    (is (= [0 2 2 4] (transform [s/INDEXED-VALS s/LAST odd?] inc [0 1 2 3])))
    (is (= [0 1 2 3] (transform [s/INDEXED-VALS (s/collect-one s/LAST) s/FIRST] (fn [i _] i) [2 1 3 0])))
    (is (= [-1 0 1 2 3] (transform [(s/indexed-vals -1) (s/collect-one s/LAST) s/FIRST] (fn [i _] i) [3 -1 0 2 1])))
    (is (= [[1 :a] [2 :b] [3 :c]] (select (s/indexed-vals 1) [:a :b :c])))
    ))

(deftest other-implicit-navs-test
  (is (= 1 (select-any ["a" true \c 10 'd] {"a" {true {\c {10 {'d 1}}}}})))
  )

(deftest vterminal-test
  (is (= {:a {:b [[1 2] 3]}}
         (multi-transform [(s/putval 1) :a (s/putval 2) :b (s/vterminal (fn [vs v] [vs v]))]
           {:a {:b 3}})))
  )

(deftest vtransform-test
  (is (= {:a 6} (vtransform [:a (s/putval 2) (s/putval 3)] (fn [vs v] (+ v (reduce + vs))) {:a 1})))
  )

(deftest compact-test
  (is (= {} (setval [:a (s/compact :b :c)] s/NONE {:a {:b {:c 1}}})))
  (is (= {:a {:d 2}} (setval [:a (s/compact :b :c)] s/NONE {:a {:b {:c 1} :d 2}})))
  (let [TREE-VALUES (recursive-path [] p (s/if-path vector? [(s/compact s/ALL) p] s/STAY))
        tree [1 [2 3] [] [4 [[5] [[6]]]]]]
    (is (= [2 4 6] (select [TREE-VALUES even?] tree)))
    (is (= [1 [3] [[[5]]]] (setval [TREE-VALUES even?] s/NONE tree)))
    )
  (is (= [{:a [{:c 1}]}]
         (setval [s/ALL (s/compact :a s/ALL :b)]
           s/NONE
           [{:a [{:b 3}]}
            {:a [{:b 2 :c 1}]}])))
  )

(deftest class-constant-test
  (let [f (fn [p] (fn [v] (str p (inc v))))]
    (is (= (str #?(:clj String :cljs js/String) 2)
           (multi-transform (s/terminal (f #?(:clj String :cljs js/String))) 1)))
    ))

#?(:clj
  (do
    (defprotocolpath FooPP)
    (extend-protocolpath FooPP String s/STAY)

    (deftest satisfies-protpath-test
      (is (satisfies-protpath? FooPP "a"))
      (is (not (satisfies-protpath? FooPP 1)))
      )))
