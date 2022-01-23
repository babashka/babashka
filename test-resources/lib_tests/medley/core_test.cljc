(ns medley.core-test
  #?(:clj (:import [clojure.lang ArityException]))
  (:require #?(:clj  [clojure.test :refer :all]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [medley.core :as m]))

(deftest test-find-first
  (testing "sequences"
    (is (= (m/find-first even? [7 3 3 2 8]) 2))
    (is (nil? (m/find-first even? [7 3 3 7 3]))))
  (testing "transducers"
    (is (= (transduce (m/find-first even?) + 0 [7 3 3 2 8]) 2))
    (is (= (transduce (m/find-first even?) + 0 [7 3 3 7 3]) 0))))

(deftest test-dissoc-in
  (is (= (m/dissoc-in {:a {:b {:c 1 :d 2}}} [:a :b :c])
         {:a {:b {:d 2}}}))
  (is (= (m/dissoc-in {:a {:b {:c 1}}} [:a :b :c])
         {}))
  (is (= (m/dissoc-in {:a {:b {:c 1} :d 2}} [:a :b :c])
         {:a {:d 2}}))
  (is (= (m/dissoc-in {:a {:b {:c 1} :d 2} :b {:c {:d 2 :e 3}}} [:a :b :c] [:b :c :d])
         {:a {:d 2} :b {:c {:e 3}}}))
  (is (= (m/dissoc-in {:a 1} [])
         {:a 1})))

(deftest test-assoc-some
  (is (= (m/assoc-some {:a 1} :b 2) {:a 1 :b 2}))
  (is (= (m/assoc-some {:a 1} :b nil) {:a 1}))
  (is (= (m/assoc-some {:a 1} :b 2 :c nil :d 3) {:a 1 :b 2 :d 3})))

(deftest test-update-existing
  (is (= (m/update-existing {:a 1} :a inc) {:a 2}))
  (is (= (m/update-existing {:a 1 :b 2} :a inc) {:a 2 :b 2}))
  (is (= (m/update-existing {:b 2} :a inc) {:b 2}))
  (is (= (m/update-existing {:a nil} :a str) {:a ""}))
  (is (= (m/update-existing {} :a str) {})))

(deftest test-update-existing-in
  (is (= (m/update-existing-in {:a 1} [:a] inc) {:a 2}))
  (is (= (m/update-existing-in {:a 1 :b 2} [:a] inc) {:a 2 :b 2}))
  (is (= (m/update-existing-in {:b 2} [:a] inc) {:b 2}))
  (is (= (m/update-existing-in {:a nil} [:a] str) {:a ""}))
  (is (= (m/update-existing-in {} [:a] str) {}))
  (is (= (m/update-existing-in {:a [:b {:c 42} :d]} [:a 1 :c] inc)
         {:a [:b {:c 43} :d]}))
  (is (= (m/update-existing-in {:a [:b {:c 42} :d]} [:a 1 :c] + 7)
         {:a [:b {:c 49} :d]}))
  (is (= (m/update-existing-in {:a [:b {:c 42} :d]} [:a 1 :c] + 3 4)
         {:a [:b {:c 49} :d]}))
  (is (= (m/update-existing-in {:a [:b {:c 42} :d]} [:a 1 :c] + 3 3 1)
         {:a [:b {:c 49} :d]}))
  (is (= (m/update-existing-in {:a [:b {:c 42} :d]} [:a 1 :c] vector 9 10 11 12 13 14)
         {:a [:b {:c [42 9 10 11 12 13 14]} :d]})))

(deftest test-map-entry
  (is (= (key (m/map-entry :a 1)) :a))
  (is (= (val (m/map-entry :a 1)) 1))
  (is (= (first  (m/map-entry :a 1)) :a))
  (is (= (second (m/map-entry :a 1)) 1))
  (is (= (type (m/map-entry :a 1))
         (type (first {:a 1})))))

(defrecord MyRecord [x])

(deftest test-map-kv
  (is (= (m/map-kv (fn [k v] [(name k) (inc v)]) {:a 1 :b 2})
         {"a" 2 "b" 3}))
  (is (= (m/map-kv (fn [k v] [(name k) (inc v)]) (sorted-map :a 1 :b 2))
         {"a" 2 "b" 3}))
  (is (= (m/map-kv (fn [k v] (m/map-entry (name k) (inc v))) {:a 1 :b 2})
         {"a" 2 "b" 3}))
  (testing "map-kv with record"
    (is (= (m/map-kv (fn [k v] (m/map-entry (name k) (inc v))) (->MyRecord 1)) {"x" 2}))))

(deftest test-map-keys
  (is (= (m/map-keys name {:a 1 :b 2})
         {"a" 1 "b" 2}))
  (is (= (m/map-keys name (sorted-map :a 1 :b 2))
         (sorted-map "a" 1 "b" 2)))
  (testing "map-keys with record"
    (is (= (m/map-keys name (->MyRecord 1)) {"x" 1}))))

(deftest test-map-vals
  (is (= (m/map-vals inc {:a 1 :b 2})
         {:a 2 :b 3}))
  (is (= (m/map-vals inc (sorted-map :a 1 :b 2))
         (sorted-map :a 2 :b 3)))
  (testing "map-vals with record"
    (is (= (m/map-vals inc (->MyRecord 1)) {:x 2})))
  (testing "multiple collections"
    (is (= (m/map-vals + {:a 1 :b 2 :c 3} {:a 4 :c 5 :d 6})
           {:a 5, :c 8}))
    (is (= (m/map-vals min
                       (sorted-map :z 10 :y 8 :x 4)
                       {:x 7, :y 14, :z 13}
                       {:x 11, :y 6, :z 9}
                       {:x 19, :y 3, :z 2}
                       {:x 4, :y 0, :z 16}
                       {:x 17, :y 14, :z 13})
           (sorted-map :x 4 :y 0 :z 2)))
    (is (= (m/map-vals #(%1 %2) {:a nil? :b some?} {:b nil})
           {:b false}))))

(deftest test-map-kv-keys
  (is (= (m/map-kv-keys + {1 2, 2 4})
         {3 2, 6 4}))
  (is (= (m/map-kv-keys + (sorted-map 1 2, 2 4))
         (sorted-map 3 2, 6 4)))
  (is (= (m/map-kv-keys str (->MyRecord 1))
         {":x1" 1})))

(deftest test-map-kv-vals
  (is (= (m/map-kv-vals + {1 2, 2 4})
         {1 3, 2 6}))
  (is (= (m/map-kv-vals + (sorted-map 1 2, 2 4))
         (sorted-map 1 3, 2 6)))
  (is (= (m/map-kv-vals str (->MyRecord 1))
         {:x ":x1"})))

(deftest test-filter-kv
  (is (= (m/filter-kv (fn [k v] (and (keyword? k) (number? v))) {"a" 1 :b 2 :c "d"})
         {:b 2}))
  (is (= (m/filter-kv (fn [k v] (= v 2)) (sorted-map "a" 1 "b" 2))
         (sorted-map "b" 2))))

(deftest test-filter-keys
  (is (= (m/filter-keys keyword? {"a" 1 :b 2})
         {:b 2}))
  (is (= (m/filter-keys #(re-find #"^b" %) (sorted-map "a" 1 "b" 2))
         (sorted-map "b" 2))))

(deftest test-filter-vals
  (is (= (m/filter-vals even? {:a 1 :b 2})
         {:b 2}))
  (is (= (m/filter-vals even? (sorted-map :a 1 :b 2))
         (sorted-map :b 2))))

(deftest test-remove-kv
  (is (= (m/remove-kv (fn [k v] (and (keyword? k) (number? v))) {"a" 1 :b 2 :c "d"})
         {"a" 1 :c "d"}))
  (is (= (m/remove-kv (fn [k v] (= v 2)) (sorted-map "a" 1 "b" 2))
         (sorted-map "a" 1))))

(deftest test-remove-keys
  (is (= (m/remove-keys keyword? {"a" 1 :b 2})
         {"a" 1}))
  (is (= (m/remove-keys #(re-find #"^b" %) (sorted-map "a" 1 "b" 2))
         {"a" 1})))

(deftest test-remove-vals
  (is (= (m/remove-vals even? {:a 1 :b 2})
         {:a 1}))
  (is (= (m/remove-keys #(re-find #"^b" %) (sorted-map "a" 1 "b" 2))
         {"a" 1})))

(deftest test-queue
  (testing "empty"
    #?(:clj  (is (instance? clojure.lang.PersistentQueue (m/queue)))
       :cljs (is (instance? cljs.core.PersistentQueue (m/queue))))
    (is (empty? (m/queue))))
  (testing "not empty"
    #?(:clj  (is (instance? clojure.lang.PersistentQueue (m/queue [1 2 3])))
       :cljs (is (instance? cljs.core.PersistentQueue (m/queue [1 2 3]))))
    (is (= (first (m/queue [1 2 3])) 1))))

(deftest test-queue?
  #?(:clj  (is (m/queue? clojure.lang.PersistentQueue/EMPTY))
     :cljs (is (m/queue? cljs.core.PersistentQueue.EMPTY)))
  (is (not (m/queue? []))))

(deftest test-boolean?
  (is (m/boolean? true))
  (is (m/boolean? false))
  (is (not (m/boolean? nil)))
  (is (not (m/boolean? "foo")))
  (is (not (m/boolean? 1))))

(deftest test-least
  (is (= (m/least) nil))
  (is (= (m/least "a") "a"))
  (is (= (m/least "a" "b") "a"))
  (is (= (m/least 3 2 5 -1 0 2) -1)))

(deftest test-greatest
  (is (= (m/greatest) nil))
  (is (= (m/greatest "a") "a"))
  (is (= (m/greatest "a" "b") "b"))
  (is (= (m/greatest 3 2 5 -1 0 2) 5)))

(deftest test-join
  (is (= (m/join [[1 2] []  [3] [4 5 6]]) [1 2 3 4 5 6]))
  (is (= (m/join (sorted-map :x 1, :y 2, :z 3)) [:x 1 :y 2 :z 3]))
  (let [a (atom 0)
        s (m/join (iterate #(do (swap! a inc) (range (inc (count %)))) ()))]
    (is (= (first s) 0))
    (is (= @a 1))
    (is (= (second s) 0))
    (is (= @a 2))))

(deftest test-deep-merge
  (is (= (m/deep-merge) nil))
  (is (= (m/deep-merge {:a 1}) {:a 1}))
  (is (= (m/deep-merge {:a 1} nil) {:a 1}))
  (is (= (m/deep-merge {:a 1} {:a 2 :b 3}) {:a 2 :b 3}))
  (is (= (m/deep-merge {:a {:b 1 :c 2}} {:a {:b 2 :d 3}}) {:a {:b 2 :c 2 :d 3}}))
  (is (= (m/deep-merge {:a {:b 1}} {:a 1}) {:a 1}))
  (is (= (m/deep-merge {:a 1} {:b 2} {:b 3 :c 4}) {:a 1 :b 3 :c 4}))
  (is (= (m/deep-merge {:a {:b {:c {:d 1}}}} {:a {:b {:c {:e 2}}}}) {:a {:b {:c {:d 1 :e 2}}}}))
  (is (= (m/deep-merge {:a {:b [1 2]}} {:a {:b [3 4]}}) {:a {:b [3 4]}}))
  (is (= (m/deep-merge (->MyRecord 1) {:x 2}) (->MyRecord 2)))
  (is (= (m/deep-merge {:a (->MyRecord 1)} {:a {:x 2 :y 3}}) {:a (map->MyRecord {:x 2 :y 3})})))

(deftest test-mapply
  (letfn [(foo [& {:keys [bar]}] bar)]
    (is (= (m/mapply foo {}) nil))
    (is (= (m/mapply foo {:baz 1}) nil))
    (is (= (m/mapply foo {:bar 1}) 1)))
  (letfn [(foo [bar & {:keys [baz]}] [bar baz])]
    (is (= (m/mapply foo 0 {}) [0 nil]))
    (is (= (m/mapply foo 0 {:baz 1}) [0 1]))
    (is (= (m/mapply foo 0 {:spam 1}) [0 nil]))
    (is (= (m/mapply foo 0 nil) [0 nil]))
    #?@(:clj  [;; BB-TEST-PATCH: bb throws Exception
               #_(is (thrown? ArityException (m/mapply foo {})))
               (is (thrown? IllegalArgumentException (m/mapply foo 0)))]
        :cljs [(is (thrown? js/Error (m/mapply foo 0)))])))

(deftest test-index-by
  (is (= (m/index-by identity [1 2 3]) {1 1, 2 2, 3 3}))
  (is (= (m/index-by inc [1 2 3]) {2 1, 3 2, 4 3}))
  (is (= (m/index-by first ["foo" "bar" "baz"]) {\f "foo", \b "baz"}))
  (is (= (m/index-by first []) {})))

(deftest test-interleave-all
  (is (= (m/interleave-all []) []))
  (is (= (m/interleave-all [1 2 3]) [1 2 3]))
  (is (= (m/interleave-all [1 2 3] [4 5 6]) [1 4 2 5 3 6]))
  (is (= (m/interleave-all [1 2 3] [4 5 6] [7 8 9]) [1 4 7 2 5 8 3 6 9]))
  (is (= (m/interleave-all [1 2] [3]) [1 3 2]))
  (is (= (m/interleave-all [1 2 3] [4 5]) [1 4 2 5 3]))
  (is (= (m/interleave-all [1] [2 3] [4 5 6]) [1 2 4 3 5 6])))

(deftest test-distinct-by
  (testing "sequences"
    (is (= (m/distinct-by count ["a" "ab" "c" "cd" "def"])
           ["a" "ab" "def"]))
    (is (= (m/distinct-by count [])
           []))
    (is (= (m/distinct-by first ["foo" "faa" "boom" "bar"])
           ["foo" "boom"])))

  (testing "transucers"
    (is (= (into [] (m/distinct-by count) ["a" "ab" "c" "cd" "def"])
           ["a" "ab" "def"]))
    (is (= (into [] (m/distinct-by count) [])
           []))
    (is (= (into [] (m/distinct-by first) ["foo" "faa" "boom" "bar"])
           ["foo" "boom"]))))

(deftest test-dedupe-by
  (testing "sequences"
    (is (= (m/dedupe-by count ["a" "b" "bc" "bcd" "cd"])
           ["a" "bc" "bcd" "cd"]))
    (is (= (m/dedupe-by count [])
           []))
    (is (= (m/dedupe-by first ["foo" "faa" "boom" "bar"])
           ["foo" "boom"])))

  (testing "transucers"
    (is (= (into [] (m/dedupe-by count) ["a" "b" "bc" "bcd" "cd"])
           ["a" "bc" "bcd" "cd"]))
    (is (= (into [] (m/dedupe-by count) [])
           []))
    (is (= (into [] (m/dedupe-by first) ["foo" "faa" "boom" "bar"])
           ["foo" "boom"]))))

(deftest test-take-upto
  (testing "sequences"
    (is (= (m/take-upto zero? [1 2 3 0 4 5 6]) [1 2 3 0]))
    (is (= (m/take-upto zero? [0 1 2 3 4 5 6]) [0]))
    (is (= (m/take-upto zero? [1 2 3 4 5 6 7]) [1 2 3 4 5 6 7])))

  (testing "tranducers"
    (is (= (into [] (m/take-upto zero?) [1 2 3 0 4 5 6]) [1 2 3 0]))
    (is (= (into [] (m/take-upto zero?) [0 1 2 3 4 5 6]) [0]))
    (is (= (into [] (m/take-upto zero?) [1 2 3 4 5 6 7]) [1 2 3 4 5 6 7]))
    (is (= (transduce (m/take-upto zero?)
                      (completing (fn [_ x] (reduced x)))
                      nil
                      [0 1 2])
           0))))

(deftest test-drop-upto
  (testing "sequences"
    (is (= (m/drop-upto zero? [1 2 3 0 4 5 6]) [4 5 6]))
    (is (= (m/drop-upto zero? [0 1 2 3 4 5 6]) [1 2 3 4 5 6]))
    (is (= (m/drop-upto zero? [1 2 3 4 5 6 7]) [])))

  (testing "transducers"
    (is (= (into [] (m/drop-upto zero?) [1 2 3 0 4 5 6]) [4 5 6]))
    (is (= (into [] (m/drop-upto zero?) [0 1 2 3 4 5 6]) [1 2 3 4 5 6]))
    (is (= (into [] (m/drop-upto zero?) [1 2 3 4 5 6 7]) []))))

(deftest test-indexed
  (testing "sequences"
    (is (= (m/indexed [:a :b :c :d])
           [[0 :a] [1 :b] [2 :c] [3 :d]]))
    (is (= (m/indexed [])
           [])))

  (testing "transducers"
    (is (= (into [] (m/indexed) [:a :b :c :d])
           [[0 :a] [1 :b] [2 :c] [3 :d]]))
    (is (= (into [] (m/indexed) [])
           []))))

(deftest test-insert-nth
  (testing "sequences"
    (is (= (m/insert-nth 0 :a [1 2 3 4]) [:a 1 2 3 4]))
    (is (= (m/insert-nth 1 :a [1 2 3 4]) [1 :a 2 3 4]))
    (is (= (m/insert-nth 3 :a [1 2 3 4]) [1 2 3 :a 4]))
    (is (= (m/insert-nth 4 :a [1 2 3 4]) [1 2 3 4 :a])))

  (testing "transducers"
    (is (= (into [] (m/insert-nth 0 :a) [1 2 3 4]) [:a 1 2 3 4]))
    (is (= (into [] (m/insert-nth 1 :a) [1 2 3 4]) [1 :a 2 3 4]))
    (is (= (into [] (m/insert-nth 3 :a) [1 2 3 4]) [1 2 3 :a 4]))
    (is (= (into [] (m/insert-nth 4 :a) [1 2 3 4]) [1 2 3 4 :a]))))

(deftest test-remove-nth
  (testing "sequences"
    (is (= (m/remove-nth 0 [1 2 3 4]) [2 3 4]))
    (is (= (m/remove-nth 1 [1 2 3 4]) [1 3 4]))
    (is (= (m/remove-nth 3 [1 2 3 4]) [1 2 3])))

  (testing "transducers"
    (is (= (into [] (m/remove-nth 0) [1 2 3 4]) [2 3 4]))
    (is (= (into [] (m/remove-nth 1) [1 2 3 4]) [1 3 4]))
    (is (= (into [] (m/remove-nth 3) [1 2 3 4]) [1 2 3]))))

(deftest test-replace-nth
  (testing "sequences"
    (is (= (m/replace-nth 0 :a [1 2 3 4]) [:a 2 3 4]))
    (is (= (m/replace-nth 1 :a [1 2 3 4]) [1 :a 3 4]))
    (is (= (m/replace-nth 3 :a [1 2 3 4]) [1 2 3 :a])))

  (testing "transducers"
    (is (= (into [] (m/replace-nth 0 :a) [1 2 3 4]) [:a 2 3 4]))
    (is (= (into [] (m/replace-nth 1 :a) [1 2 3 4]) [1 :a 3 4]))
    (is (= (into [] (m/replace-nth 3 :a) [1 2 3 4]) [1 2 3 :a]))))

(deftest test-abs
  (is (= (m/abs -3) 3))
  (is (= (m/abs 2) 2))
  (is (= (m/abs -2.1) 2.1))
  (is (= (m/abs 1.8) 1.8))
  #?@(:clj [(is (= (m/abs -1/3) 1/3))
            (is (= (m/abs 1/2) 1/2))
            (is (= (m/abs 3N) 3N))
            (is (= (m/abs -4N) 4N))]))

(deftest test-deref-swap!
  (let [a (atom 0)]
    (is (= (m/deref-swap! a inc) 0))
    (is (= @a 1))
    (is (= (m/deref-swap! a inc) 1))
    (is (= @a 2))))

(deftest test-deref-reset!
  (let [a (atom 0)]
    (is (= (m/deref-reset! a 3) 0))
    (is (= @a 3))
    (is (= (m/deref-reset! a 1) 3))
    (is (= @a 1))))

(deftest test-ex-message
  (is (= (m/ex-message (ex-info "foo" {})) "foo"))
  (is (= (m/ex-message (new #?(:clj Exception :cljs js/Error) "bar")) "bar")))

(deftest test-ex-cause
  (let [cause (new #?(:clj Exception :cljs js/Error) "foo")]
    (is (= (m/ex-cause (ex-info "foo" {} cause)) cause))
    #?(:clj (is (= (m/ex-cause (Exception. "foo" cause)) cause)))))

(deftest test-uuid?
  (let [x #uuid "d1a4adfa-d9cf-4aa5-9f05-a15365d1bfa6"]
    (is (m/uuid? x))
    (is (not (m/uuid? 2)))
    (is (not (m/uuid? (str x))))
    (is (not (m/uuid? nil)))))

(deftest test-uuid
  (let [x (m/uuid "d1a4adfa-d9cf-4aa5-9f05-a15365d1bfa6")]
    (is (instance? #?(:clj java.util.UUID :cljs cljs.core.UUID) x))
    (is (= x #uuid "d1a4adfa-d9cf-4aa5-9f05-a15365d1bfa6"))))

(deftest test-random-uuid
  (let [x (m/random-uuid)
        y (m/random-uuid)]
    (is (instance? #?(:clj java.util.UUID :cljs cljs.core.UUID) x))
    (is (instance? #?(:clj java.util.UUID :cljs cljs.core.UUID) y))
    (is (not= x y))))

;; BB-TEST-PATCH: Not available yet for latest maven release
#_(deftest test-regexp?
  (is (m/regexp? #"x"))
  (is (not (m/regexp? "x")))
  (is (not (m/regexp? nil))))
