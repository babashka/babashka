;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns ^{:doc "A caching library for Clojure."
      :author "Fogus"}
  clojure.core.cache-test
  (:use [clojure.core.cache] :reload)
  (:use [clojure.test])
  (:import (java.lang.ref ReferenceQueue SoftReference)
           (java.util.concurrent ConcurrentHashMap)))

(println "\nTesting with Clojure" (clojure-version))

(deftest test-basic-cache-lookup
  (testing "that the BasicCache can lookup as expected"
    (is (= :robot (lookup (miss (BasicCache. {}) '(servo) :robot) '(servo))))))

(defn do-dot-lookup-tests [c]
  (are [expect actual] (= expect actual)
       1   (lookup c :a)
       2   (lookup c :b)
       42  (lookup c :c 42)
       nil (lookup c :c)))

(defn do-ilookup-tests [c]
  (are [expect actual] (= expect actual)
       1   (:a c)
       2   (:b c)
       42  (:X c 42)
       nil (:X c)))

(defn do-assoc [c]
  (are [expect actual] (= expect actual)
       1   (:a (assoc c :a 1))
       nil (:a (assoc c :b 1))))

(defn do-dissoc [c]
  (are [expect actual] (= expect actual)
       2   (:b (dissoc c :a))
       nil (:a (dissoc c :a))
       nil (:b (-> c (dissoc :a) (dissoc :b)))
       0   (count (-> c (dissoc :a) (dissoc :b)))))

(defn do-getting [c]
  (are [actual expect] (= expect actual)
       (get c :a) 1
       (get c :e) nil
       (get c :e 0) 0
       (get c :b 0) 2
       (get c :f 0) nil

       (get-in c [:c :e]) 4
       (get-in c '(:c :e)) 4
       (get-in c [:c :x]) nil
       (get-in c [:f]) nil
       (get-in c [:g]) false
       (get-in c [:h]) nil
       (get-in c []) c
       (get-in c nil) c

       (get-in c [:c :e] 0) 4
       (get-in c '(:c :e) 0) 4
       (get-in c [:c :x] 0) 0
       (get-in c [:b] 0) 2
       (get-in c [:f] 0) nil
       (get-in c [:g] 0) false
       (get-in c [:h] 0) 0
       (get-in c [:x :y] {:y 1}) {:y 1}
       (get-in c [] 0) c
       (get-in c nil 0) c))

(defn do-finding [c]
  (are [expect actual] (= expect actual)
       (find c :a) [:a 1]
       (find c :b) [:b 2]
       (find c :c) nil
       (find c nil) nil))

(defn do-contains [c]
  (are [expect actual] (= expect actual)
       (contains? c :a) true
       (contains? c :b) true
       (contains? c :c) false
       (contains? c nil) false))


(def big-map {:a 1, :b 2, :c {:d 3, :e 4}, :f nil, :g false, nil {:h 5}})
(def small-map {:a 1 :b 2})

(deftest test-basic-cache-ilookup
  (testing "counts"
    (is (= 0 (count (BasicCache. {}))))
    (is (= 1 (count (BasicCache. {:a 1})))))
  (testing "that the BasicCache can lookup via keywords"
    (do-ilookup-tests (BasicCache. small-map)))
  (testing "that the BasicCache can .lookup"
    (do-dot-lookup-tests (BasicCache. small-map)))
  (testing "assoc and dissoc for BasicCache"
    (do-assoc (BasicCache. {}))
    (do-dissoc (BasicCache. {:a 1 :b 2})))
  (testing "that get and cascading gets work for BasicCache"
    (do-getting (BasicCache. big-map)))
  (testing "that finding works for BasicCache"
    (do-finding (BasicCache. small-map)))
  (testing "that contains? works for BasicCache"
    (do-contains (BasicCache. small-map))))

(deftest test-fifo-cache-ilookup
  (testing "that the FifoCache can lookup via keywords"
    (do-ilookup-tests (FIFOCache. small-map clojure.lang.PersistentQueue/EMPTY 2)))
  (testing "that the FifoCache can lookup via keywords"
    (do-dot-lookup-tests (FIFOCache. small-map clojure.lang.PersistentQueue/EMPTY 2)))
  (testing "assoc and dissoc for FifoCache"
    (do-assoc (FIFOCache. {} clojure.lang.PersistentQueue/EMPTY 2))
    (do-dissoc (FIFOCache. {:a 1 :b 2} clojure.lang.PersistentQueue/EMPTY 2)))
  (testing "that get and cascading gets work for FifoCache"
    (do-getting (FIFOCache. big-map clojure.lang.PersistentQueue/EMPTY 2)))
  (testing "that finding works for FifoCache"
    (do-finding (FIFOCache. small-map clojure.lang.PersistentQueue/EMPTY 2)))
  (testing "that contains? works for FifoCache"
    (do-contains (FIFOCache. small-map clojure.lang.PersistentQueue/EMPTY 2)))
  (testing "that FIFO caches starting with less elements than the threshold work"
    (let [C (fifo-cache-factory (sorted-map :a 1, :b 2) :threshold 3)]
      (are [x y] (= x y)
           {:a 1, :b 2, :c 3} (.cache (assoc C :c 3))
           {:d 4, :b 2, :c 3} (.cache (assoc C :c 3 :d 4))))))

(deftest test-lru-cache-ilookup
  (testing "that the LRUCache can lookup via keywords"
    (do-ilookup-tests (LRUCache. small-map {} 0 2)))
  (testing "that the LRUCache can lookup via keywords"
    (do-dot-lookup-tests (LRUCache. small-map {} 0 2)))
  (testing "assoc and dissoc for LRUCache"
    (do-assoc (LRUCache. {} {} 0 2))
    (do-dissoc (LRUCache. {:a 1 :b 2} {} 0 2)))
  (testing "that get and cascading gets work for LRUCache"
    (do-getting (LRUCache. big-map {} 0 2)))
  (testing "that finding works for LRUCache"
    (do-finding (LRUCache. small-map {} 0 2)))
  (testing "that contains? works for LRUCache"
    (do-contains (LRUCache. small-map {} 0 2))))

(deftest test-lru-cache
  (testing "LRU-ness with empty cache and threshold 2"
    (let [C (lru-cache-factory {} :threshold 2)]
      (are [x y] (= x y)
           {:a 1, :b 2} (-> C (assoc :a 1) (assoc :b 2) .cache)
           {:b 2, :c 3} (-> C (assoc :a 1) (assoc :b 2) (assoc :c 3) .cache)
           {:a 1, :c 3} (-> C (assoc :a 1) (assoc :b 2) (hit :a) (assoc :c 3) .cache))))
  (testing "LRU-ness with seeded cache and threshold 4"
    (let [C (lru-cache-factory {:a 1, :b 2} :threshold 4)]
      (are [x y] (= x y)
           {:a 1, :b 2, :c 3, :d 4} (-> C (assoc :c 3) (assoc :d 4) .cache)
           {:a 1, :c 3, :d 4, :e 5} (-> C (assoc :c 3) (assoc :d 4) (hit :c) (hit :a) (assoc :e 5) .cache))))
  (testing "regressions against LRU eviction before threshold met"
    (is (= {:b 3 :a 4}
           (-> (clojure.core.cache/lru-cache-factory {} :threshold 2)
               (assoc :a 1)
               (assoc :b 2)
               (assoc :b 3)
               (assoc :a 4)
               .cache)))

    (is (= {:e 6, :d 5, :c 4}
           (-> (clojure.core.cache/lru-cache-factory {} :threshold 3)
               (assoc :a 1)
               (assoc :b 2)
               (assoc :b 3)
               (assoc :c 4)
               (assoc :d 5)
               (assoc :e 6)
               .cache)))

    (is (= {:a 1 :b 3}
           (-> (clojure.core.cache/lru-cache-factory {} :threshold 2)
               (assoc :a 1)
               (assoc :b 2)
               (assoc :b 3)
               .cache))))

  (is (= {:d 4 :e 5}
         (-> (lru-cache-factory {} :threshold 2)
             (hit :x)
             (hit :y)
             (hit :z)
             (assoc :a 1)
             (assoc :b 2)
             (assoc :c 3)
             (assoc :d 4)
             (assoc :e 5)
             .cache))))

(defn sleepy [e t] (Thread/sleep t) e)

(deftest test-ttl-cache-ilookup
  (let [five-secs  (+ 5000 (System/currentTimeMillis))
        big-time   (into {} (for [[k _] big-map] [k [0 five-secs]]))
        big-q      (into clojure.lang.PersistentQueue/EMPTY
                         (for [[k _] big-map] [k 0 five-secs]))
        small-time (into {} (for [[k _] small-map] [k [0 five-secs]]))
        small-q    (into clojure.lang.PersistentQueue/EMPTY
                         (for [[k _] small-map] [k 0 five-secs]))]
    (testing "that the TTLCacheQ can lookup via keywords"
      (do-ilookup-tests (TTLCacheQ. small-map small-time small-q 1 2000)))
    (testing "that the TTLCacheQ can lookup via keywords"
      (do-dot-lookup-tests (TTLCacheQ. small-map small-time small-q 1 2000)))
    (testing "assoc and dissocQ for TTLCacheQ"
      (do-assoc (TTLCacheQ. {} {} clojure.lang.PersistentQueue/EMPTY 1 2000))
      (do-dissoc (TTLCacheQ. {:a 1 :b 2}
                             {:a [0 five-secs] :b [0 five-secs]}
                             (into clojure.lang.PersistentQueue/EMPTY
                                   [[:a 0 five-secs] [:b 0 five-secs]])
                             1
                             2000)))
    (testing "that get and cascading gets work for TTLCacheQ"
      (do-getting (TTLCacheQ. big-map big-time big-q 1 2000)))
    (testing "that finding works for TTLCacheQ"
        (do-finding (TTLCacheQ. small-map small-time small-q 1 2000)))
    (testing "that contains? works for TTLCacheQ"
        (do-contains (TTLCacheQ. small-map small-time small-q 1 2000)))))

(defn- ttl-q-check
  [start nap [k g t]]
  ;; BB_TEST_PATCH: widened tolerance from 100 to 500 for slow CI
  [k g (<= start (+ start nap) t (+ start nap 500))])

(deftest test-ttl-cache
  (testing "TTL-ness with empty cache"
    (let [start (System/currentTimeMillis)
          C     (ttl-cache-factory {} :ttl 500)
          C'    (-> C (assoc :a 1) (assoc :b 2))]
      (are [x y] (= x y)
           [[:a 1 true], [:b 2 true]] (map (partial ttl-q-check start 0) (.q C'))
           {:a 1, :b 2}               (.cache C')
           3                          (.gen C')))
    (let [start (System/currentTimeMillis)
          C     (ttl-cache-factory {} :ttl 500)
          C'    (-> C (assoc :a 1) (assoc :b 2) (sleepy 700) (assoc :c 3))]
      (are [x y] (= x y)
           [[:c 3 true]] (map (partial ttl-q-check start 700) (.q C'))
           {:c 3}        (.cache C')
           4             (.gen C'))))
  (testing "TTL cache does not return a value that has expired."
    (let [C (ttl-cache-factory {} :ttl 500)]
      (is (nil? (-> C (assoc :a 1) (sleepy 700) (lookup :a))))))
  (testing "TTL cache does not contain a value that was removed from underlying cache."
    (let [underlying-cache (lru-cache-factory {} :threshold 1)
          C (ttl-cache-factory underlying-cache :ttl 360000)]
      (is (not (-> C (assoc :a 1) (assoc :b 2) (has? :a)))))))

(deftest test-lu-cache-ilookup
  (testing "that the LUCache can lookup via keywords"
    (do-ilookup-tests (LUCache. small-map {} 2)))
  (testing "that the LUCache can lookup via keywords"
    (do-dot-lookup-tests (LUCache. small-map {} 2)))
  (testing "assoc and dissoc for LUCache"
    (do-assoc (LUCache. {} {}  2))
    (do-dissoc (LUCache. {:a 1 :b 2} {} 2))))

(deftest test-lu-cache
  (testing "LU-ness with empty cache"
    (let [C (lu-cache-factory {} :threshold 2)]
      (are [x y] (= x y)
           {:a 1, :b 2} (-> C (assoc :a 1) (assoc :b 2) .cache)
           {:b 2, :c 3} (-> C (assoc :a 1) (assoc :b 2) (hit :b) (assoc :c 3) .cache)
           {:b 2, :c 3} (-> C (assoc :a 1) (assoc :b 2) (hit :b) (hit :b) (hit :a) (assoc :c 3) .cache))))
  (testing "LU-ness with seeded cache"
    (let [C (lu-cache-factory {:a 1, :b 2} :threshold 4)]
      (are [x y] (= x y)
           {:a 1, :b 2, :c 3, :d 4} (-> C (assoc :c 3) (assoc :d 4) .cache)
           {:a 1, :c 3, :d 4, :e 5} (-> C (assoc :c 3) (assoc :d 4) (hit :a) (assoc :e 5) .cache)
           {:b 2, :c 3, :d 4, :e 5} (-> C (assoc :c 3) (assoc :d 4)  (hit :b) (hit :c) (hit :d) (assoc :e 5) .cache))))
  (testing "regressions against LU eviction before threshold met"
    (is (= (-> (clojure.core.cache/lu-cache-factory {} :threshold 2)
               (assoc :a 1)
               (assoc :b 2)
               (assoc :b 3)
               (assoc :a 4))
           {:b 3 :a 4}))

    (is (= (-> (clojure.core.cache/lu-cache-factory {} :threshold 3)
               (assoc :a 1)
               (assoc :b 2)
               (assoc :b 3)
               (assoc :c 4)
               (assoc :d -5)
               ;; ensure that the test result does not rely on
               ;; arbitrary tie-breakers in number of hits
               (assoc :d 5)
               (assoc :e 6))
           {:e 6, :d 5, :b 3}))

    (is (= {:a 1 :b 3}
           (-> (clojure.core.cache/lu-cache-factory {} :threshold 2)
               (assoc :a 1)
               (assoc :b 2)
               (assoc :b 3)
               .cache)))

    (is (= {:c 3 :d 4}
           (-> (clojure.core.cache/lu-cache-factory {:a 1 :b 2} :threshold 2)
               (dissoc :a)
               (assoc :c 3)
               (assoc :d 4)
               .cache)))))

;; # LIRS

(defn- lirs-map [lirs]
  {:cache (.cache lirs)
   :lruS (.lruS lirs)
   :lruQ (.lruQ lirs)
   :tick (.tick lirs)
   :limitS (.limitS lirs)
   :limitQ (.limitQ lirs)})

(deftest test-LIRSCache
  (testing "that the LIRSCache can lookup as expected"
    (is (= :robot (lookup (miss (seed (LIRSCache. {} {} {} 0 1 1) {}) '(servo) :robot) '(servo)))))

  (testing "a hit of a LIR block:

L LIR block
H HIR block
N non-resident HIR block


          +-----------------------------+   +----------------+
          |           HIT 4             |   |     HIT 8      |
          |                             v   |                |
          |                                 |                |
    H 5   |                           L 4   |                v
    H 3   |                           H 5   |
    N 2   |                           H 3   |              L 8
    L 1   |                           N 2   |              L 4
    N 6   |                           L 1   |              H 5
    N 9   |                           N 6   |              H 3
    L 4---+ 5                         N 9   | 5            N 2     5
    L 8     3                         L 8---+ 3            L 1     3

      S     Q                           S     Q              S     Q

"
    (let [lirs (LIRSCache. {:1 1 :3 3 :4 4 :5 5 :8 8}
                           {:5 7 :3 6 :2 5 :1 4 :6 3 :9 2 :4 1 :8 0}
                           {:5 1 :3 0} 7 3 2)]
      (testing "hit 4"
        (is (= (lirs-map (hit lirs :4))
               (lirs-map (LIRSCache. {:1 1 :3 3 :4 4 :5 5 :8 8}
                                     {:5 7 :3 6 :2 5 :1 4 :6 3 :9 2 :4 8 :8 0}
                                     {:5 1 :3 0} 8 3 2)))))
      (testing "hit 8 prunes the stack"
        (is (= (lirs-map (-> lirs (hit :4) (hit :8)))
               (lirs-map (LIRSCache. {:1 1 :3 3 :4 4 :5 5 :8 8}
                                     {:5 7 :3 6 :2 5 :1 4 :4 8 :8 9}
                                     {:5 1 :3 0} 9 3 2)))))))
  (testing "a hit of a HIR block:

L LIR block
H HIR block
N non-resident HIR block


                     HIT 3                                  HIT 5
         +-----------------------------+         +------------------+-----+
         |                             |         |                  |     |
    L 4  |+----------------------------+-----+   |                  v     |
    L 8  ||                            v     |   |                        |
    H 5  ||                                  v   |                H 5     v
    H 3-- |                          L 3         |                L 3
    N 2   | 5                        L 4     1   |                L 4     5
    L 1---+ 3                        L 8     5---+                L 8     1

      S     Q                          S     Q                      S     Q

"
    (let [lirs (LIRSCache. {:1 1 :3 3 :4 4 :5 5 :8 8}
                           {:4 9 :8 8 :5 7 :3 6 :2 5 :1 4}
                           {:5 1 :3 0} 9 3 2)]
      (testing "hit 3 prunes the stack and moves oldest block of lruS to lruQ"
        (is (= (lirs-map (hit lirs :3))
               {:cache {:1 1 :3 3 :4 4 :5 5 :8 8}
                :lruS {:3 10 :4 9 :8 8}
                :lruQ {:1 10 :5 1}
                :tick 10 :limitS 3 :limitQ 2})))
      (testing "hit 5 adds the block to lruS"
        (is (= (lirs-map (-> lirs (hit :3) (hit :5)))
               {:cache {:1 1 :3 3 :4 4 :5 5 :8 8}
                :lruS {:5 11 :3 10 :4 9 :8 8}
                :lruQ {:5 11 :1 10}
                :tick 11 :limitS 3 :limitQ 2})))))
  (testing "a miss:

L LIR block
H HIR block
N non-resident HIR block


                     MISS 7                          MISS 9                    MISS 5
             ---------------------+-----+    -----------------+-----+     +-------------------+
                                  |     |                     v     |     |                   |
                                  v     |                           |     |                   v
                                        |                   H 9  + -| - - +
                                H 7     |                   H 7  |  |                       L 5  +--+
    H 5                         H 5     v                   N 5- +  v                       H 9  |  v
    L 3                         L 3                         L 3                             N 7  |
    L 4     5                   L 4     7                   L 4     9                       L 3  |  8
    L 8     1                   L 8     5                   L 8--+  7                       L 4  |  9
                                                                 +-------------------------------+
      S     Q                     S     Q                     S     Q                         S     Q


"
    (let [lirs (LIRSCache. {:1 1 :3 3 :4 4 :5 5 :8 8}
                           {:5 11 :3 10 :4 9 :8 8}
                           {:5 11 :1 10} 11 3 2)]
      (testing "miss 7 adds the block to lruS and lruQ and removes the oldest block in lruQ"
        (is (= (lirs-map (miss lirs :7 7))
               {:cache {:3 3 :4 4 :5 5 :8 8 :7 7}
                :lruS {:7 12 :5 11 :3 10 :4 9 :8 8}
                :lruQ {:7 12 :5 11}
                :tick 12 :limitS 3 :limitQ 2})))
      (testing "miss 9 makes 5 a non-resident HIR block"
        (is (= (lirs-map (-> lirs (miss :7 7) (miss :9 9)))
               {:cache {:3 3 :4 4 :8 8 :7 7 :9 9}
                :lruS {:9 13 :7 12 :5 11 :3 10 :4 9 :8 8}
                :lruQ {:9 13 :7 12}
                :tick 13 :limitS 3 :limitQ 2})))
      (testing "miss 5, a non-resident HIR block becomes a new LIR block"
        (is (= (lirs-map (-> lirs (miss :7 7) (miss :9 9) (miss :5 5)))
               {:cache {:3 3 :4 4 :8 8 :9 9 :5 5}
                :lruS {:5 14 :9 13 :7 12 :3 10 :4 9}
                :lruQ {:8 14 :9 13}
                :tick 14 :limitS 3 :limitQ 2}))))))

(deftest test-soft-cache-ilookup
  (testing "counts"
    (is (= 0 (count (soft-cache-factory {}))))
    (is (= 1 (count (soft-cache-factory {:a 1})))))
  (testing "that the SoftCache can lookup via keywords"
    (do-ilookup-tests (soft-cache-factory small-map)))
  (testing "that the SoftCache can .lookup"
    (do-dot-lookup-tests (soft-cache-factory small-map)))
  (testing "that get and cascading gets work for SoftCache"
    (do-getting (soft-cache-factory big-map)))
  (testing "that finding works for SoftCache"
    (do-finding (soft-cache-factory small-map)))
  (testing "that contains? works for SoftCache"
    (do-contains (soft-cache-factory small-map))))

(deftest test-clear-soft-cache!
  (let [rq (ReferenceQueue.)
        ref (SoftReference. :bar rq)
        cache (doto (ConcurrentHashMap.)
                (.put :foo ref))
        rcache (doto (ConcurrentHashMap.)
                 (.put ref :foo))
        _ (clear-soft-cache! cache rcache rq)]
    (is (contains? cache :foo) (str cache))
    (is (contains? rcache ref) (str rcache))
    (.clear ref)
    (.enqueue ref)
    (is (not (.get ref)))
    (let [_ (clear-soft-cache! cache rcache rq)]
      (is (not (contains? cache :foo)))
      (is (not (contains? rcache ref))))))

(deftest test-soft-cache
  (let [ref (atom nil)
        old-make-reference make-reference]
    (with-redefs [make-reference (fn [& args]
                                   (reset! ref (apply old-make-reference args))
                                   @ref)]
      (let [old-soft-cache (soft-cache-factory {:foo1 :bar})
            r @ref
            soft-cache (assoc old-soft-cache :foo2 :baz)]
        (is (and r (= :bar (.get r))))
        (.clear r)
        (.enqueue r)
        (is (nil? (lookup soft-cache :foo1)))
        (is (nil? (lookup old-soft-cache :foo1)))
        (is (= :quux (lookup soft-cache :foo1 :quux)))
        (is (= :quux (lookup old-soft-cache :foo1 :quux)))
        (is (= :quux (lookup soft-cache :foo3 :quux)))
        (is (= :quux (lookup old-soft-cache :foo3 :quux)))
        (is (not (has? soft-cache :foo1)))
        (is (not (has? old-soft-cache :foo1)))))))

(deftest test-soft-cache-eviction-handling
  (let [ref (atom nil)
        old-make-reference make-reference]
    (with-redefs [make-reference (fn [& args]
                                   (reset! ref (apply old-make-reference args))
                                   @ref)]
      (let [cache (soft-cache-factory {})]
        (miss cache :foo "foo")
        (.enqueue @ref)
        (evict cache :foo)))))

(deftest test-equiv
  (is (= (fifo-cache-factory {:a 1 :c 3} :threshold 3)
         (fifo-cache-factory {:a 1 :c 3} :threshold 3))))

(deftest test-persistent-cons
  (is (let [starts-with-a (fifo-cache-factory {:a 1} :threshold 3)]
        (= (fifo-cache-factory {:a 1 :c 3} :threshold 3)
           (conj starts-with-a [:c 3])
           (conj starts-with-a {:c 3})))))

(deftest evict-with-object-exception
  (let [thing (proxy [Object] []
                (equals [obj] (throw (new Exception "Boom!"))))]
    (are [x y] (= x y)
      {:b 2} (-> (lru-cache-factory {:a thing, :b 2}) (evict :a) (.cache))
      {:b 2} (-> (lu-cache-factory {:a thing, :b 2}) (evict :a) (.cache))
      {:b 2} (-> (fifo-cache-factory {:a thing, :b 2}) (evict :a) (.cache)))))

(deftest test-cache-iterable
  (let [c (fifo-cache-factory {:a 1 :b 2} :threshold 10)]
    (is (= #{:a :b} (set (iterator-seq (.iterator (keys c))))))))

(deftest test-fifo-miss-does-not-drop-ccache-39
  (let [c (fifo-cache-factory {:a 1 :b 2} :threshold 2)]
    (is (= #{:a :c} (set (-> c (evict :b) (miss :c 42) (.q)))))
    (is (= #{:c :d} (set (-> c (evict :b) (miss :c 42) (miss :d 43) (.q)))))))
