(ns clojure.core.cache-test
  (:use [clojure.core.cache])
  (:use [clojure.test])
  (:import (java.lang.ref ReferenceQueue SoftReference)
           (java.util.concurrent ConcurrentHashMap)))

(println "\nTesting with Babashka" (System/getProperty "babashka.version"))

;; Helper fns testing the public map/protocol API

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

(deftest test-basic-cache-lookup
  (testing "that the BasicCache can lookup as expected"
    (is (= :robot (lookup (miss (basic-cache-factory {}) '(servo) :robot) '(servo))))))

(deftest test-basic-cache-ilookup
  (testing "counts"
    (is (= 0 (count (basic-cache-factory {}))))
    (is (= 1 (count (basic-cache-factory {:a 1})))))
  (testing "that the BasicCache can lookup via keywords"
    (do-ilookup-tests (basic-cache-factory small-map)))
  (testing "assoc and dissoc for BasicCache"
    (do-assoc (basic-cache-factory {}))
    (do-dissoc (basic-cache-factory {:a 1 :b 2})))
  (testing "that get and cascading gets work for BasicCache"
    (do-getting (basic-cache-factory big-map)))
  (testing "that finding works for BasicCache"
    (do-finding (basic-cache-factory small-map)))
  (testing "that contains? works for BasicCache"
    (do-contains (basic-cache-factory small-map))))

(deftest test-fifo-cache-ilookup
  (testing "that the FifoCache can lookup via keywords"
    (do-ilookup-tests (fifo-cache-factory small-map :threshold 2)))
  (testing "assoc and dissoc for FifoCache"
    (do-assoc (fifo-cache-factory {} :threshold 2))
    (do-dissoc (fifo-cache-factory {:a 1 :b 2} :threshold 32)))
  (testing "that get and cascading gets work for FifoCache"
    (do-getting (fifo-cache-factory big-map :threshold 32)))
  (testing "that finding works for FifoCache"
    (do-finding (fifo-cache-factory small-map :threshold 32)))
  (testing "that contains? works for FifoCache"
    (do-contains (fifo-cache-factory small-map :threshold 32)))
  (testing "that FIFO caches starting with less elements than the threshold work"
    (let [C (fifo-cache-factory (sorted-map :a 1, :b 2) :threshold 3)]
      (are [x y] (= x y)
           {:a 1, :b 2, :c 3} (into {} (assoc C :c 3))
           {:d 4, :b 2, :c 3} (into {} (assoc C :c 3 :d 4))))))

(deftest test-lru-cache-ilookup
  (testing "that the LRUCache can lookup via keywords"
    (do-ilookup-tests (lru-cache-factory small-map :threshold 2)))
  (testing "assoc and dissoc for LRUCache"
    (do-assoc (lru-cache-factory {} :threshold 2))
    (do-dissoc (lru-cache-factory {:a 1 :b 2} :threshold 32)))
  (testing "that get and cascading gets work for LRUCache"
    (do-getting (lru-cache-factory big-map :threshold 32)))
  (testing "that finding works for LRUCache"
    (do-finding (lru-cache-factory small-map :threshold 32)))
  (testing "that contains? works for LRUCache"
    (do-contains (lru-cache-factory small-map :threshold 32))))

(deftest test-lru-cache
  (testing "LRU-ness with empty cache and threshold 2"
    (let [C (lru-cache-factory {} :threshold 2)]
      (are [x y] (= x y)
           {:a 1, :b 2} (into {} (-> C (assoc :a 1) (assoc :b 2)))
           {:b 2, :c 3} (into {} (-> C (assoc :a 1) (assoc :b 2) (assoc :c 3)))
           {:a 1, :c 3} (into {} (-> C (assoc :a 1) (assoc :b 2) (hit :a) (assoc :c 3))))))
  (testing "LRU-ness with seeded cache and threshold 4"
    (let [C (lru-cache-factory {:a 1, :b 2} :threshold 4)]
      (are [x y] (= x y)
           {:a 1, :b 2, :c 3, :d 4} (into {} (-> C (assoc :c 3) (assoc :d 4)))
           {:a 1, :c 3, :d 4, :e 5} (into {} (-> C (assoc :c 3) (assoc :d 4) (hit :c) (hit :a) (assoc :e 5))))))
  (testing "regressions against LRU eviction before threshold met"
    (is (= {:b 3 :a 4}
           (into {} (-> (lru-cache-factory {} :threshold 2)
                        (assoc :a 1)
                        (assoc :b 2)
                        (assoc :b 3)
                        (assoc :a 4)))))

    (is (= {:e 6, :d 5, :c 4}
           (into {} (-> (lru-cache-factory {} :threshold 3)
                        (assoc :a 1)
                        (assoc :b 2)
                        (assoc :b 3)
                        (assoc :c 4)
                        (assoc :d 5)
                        (assoc :e 6)))))

    (is (= {:a 1 :b 3}
           (into {} (-> (lru-cache-factory {} :threshold 2)
                        (assoc :a 1)
                        (assoc :b 2)
                        (assoc :b 3))))))

  (is (= {:d 4 :e 5}
         (into {} (-> (lru-cache-factory {} :threshold 2)
                      (hit :x)
                      (hit :y)
                      (hit :z)
                      (assoc :a 1)
                      (assoc :b 2)
                      (assoc :c 3)
                      (assoc :d 4)
                      (assoc :e 5))))))

(defn sleepy [e t] (Thread/sleep t) e)

(deftest test-ttl-cache-ilookup
  (let [C (ttl-cache-factory small-map :ttl 5000)]
    (testing "that the TTLCacheQ can lookup via keywords"
      (do-ilookup-tests C))
    (testing "assoc and dissoc for TTLCacheQ"
      (do-assoc (ttl-cache-factory {} :ttl 5000))
      (do-dissoc (ttl-cache-factory {:a 1 :b 2} :ttl 5000)))
    (testing "that get and cascading gets work for TTLCacheQ"
      (do-getting (ttl-cache-factory big-map :ttl 5000)))
    (testing "that finding works for TTLCacheQ"
      (do-finding (ttl-cache-factory small-map :ttl 5000)))
    (testing "that contains? works for TTLCacheQ"
      (do-contains (ttl-cache-factory small-map :ttl 5000)))))

(deftest test-ttl-cache
  (testing "TTL-ness with empty cache"
    (let [C (ttl-cache-factory {} :ttl 500)
          C' (-> C (assoc :a 1) (assoc :b 2))]
      (is (= {:a 1, :b 2} (into {} C'))))
    (let [C (ttl-cache-factory {} :ttl 500)
          C' (-> C (assoc :a 1) (assoc :b 2) (sleepy 700) (assoc :c 3))]
      (is (= {:c 3} (into {} C')))))
  (testing "TTL cache does not return a value that has expired."
    (let [C (ttl-cache-factory {} :ttl 500)]
      (is (nil? (-> C (assoc :a 1) (sleepy 700) (lookup :a))))))
  (testing "TTL cache does not contain a value that was removed from underlying cache."
    (let [underlying-cache (lru-cache-factory {} :threshold 1)
          C (ttl-cache-factory underlying-cache :ttl 360000)]
      (is (not (-> C (assoc :a 1) (assoc :b 2) (has? :a)))))))

(deftest test-lu-cache-ilookup
  (testing "that the LUCache can lookup via keywords"
    (do-ilookup-tests (lu-cache-factory small-map :threshold 2)))
  (testing "assoc and dissoc for LUCache"
    (do-assoc (lu-cache-factory {} :threshold 2))
    (do-dissoc (lu-cache-factory {:a 1 :b 2} :threshold 32))))

(deftest test-lu-cache
  (testing "LU-ness with empty cache"
    (let [C (lu-cache-factory {} :threshold 2)]
      (are [x y] (= x y)
           {:a 1, :b 2} (into {} (-> C (assoc :a 1) (assoc :b 2)))
           {:b 2, :c 3} (into {} (-> C (assoc :a 1) (assoc :b 2) (hit :b) (assoc :c 3)))
           {:b 2, :c 3} (into {} (-> C (assoc :a 1) (assoc :b 2) (hit :b) (hit :b) (hit :a) (assoc :c 3))))))
  (testing "LU-ness with seeded cache"
    (let [C (lu-cache-factory {:a 1, :b 2} :threshold 4)]
      (are [x y] (= x y)
           {:a 1, :b 2, :c 3, :d 4} (into {} (-> C (assoc :c 3) (assoc :d 4)))
           {:a 1, :c 3, :d 4, :e 5} (into {} (-> C (assoc :c 3) (assoc :d 4) (hit :a) (assoc :e 5)))
           {:b 2, :c 3, :d 4, :e 5} (into {} (-> C (assoc :c 3) (assoc :d 4) (hit :b) (hit :c) (hit :d) (assoc :e 5))))))
  (testing "regressions against LU eviction before threshold met"
    (is (= (into {} (-> (lu-cache-factory {} :threshold 2)
                        (assoc :a 1)
                        (assoc :b 2)
                        (assoc :b 3)
                        (assoc :a 4)))
           {:b 3 :a 4}))

    (is (= (into {} (-> (lu-cache-factory {} :threshold 3)
                        (assoc :a 1)
                        (assoc :b 2)
                        (assoc :b 3)
                        (assoc :c 4)
                        (assoc :d -5)
                        (assoc :d 5)
                        (assoc :e 6)))
           {:e 6, :d 5, :b 3}))

    (is (= {:a 1 :b 3}
           (into {} (-> (lu-cache-factory {} :threshold 2)
                        (assoc :a 1)
                        (assoc :b 2)
                        (assoc :b 3)))))

    (is (= {:c 3 :d 4}
           (into {} (-> (lu-cache-factory {:a 1 :b 2} :threshold 2)
                        (dissoc :a)
                        (assoc :c 3)
                        (assoc :d 4)))))))

(deftest test-LIRSCache
  (testing "that the LIRSCache can lookup as expected"
    (is (= :robot (lookup (miss (seed (lirs-cache-factory {} :s-history-limit 1 :q-history-limit 1) {}) '(servo) :robot) '(servo))))))

(deftest test-soft-cache-ilookup
  (testing "counts"
    (is (= 0 (count (soft-cache-factory {}))))
    (is (= 1 (count (soft-cache-factory {:a 1})))))
  (testing "that the SoftCache can lookup via keywords"
    (do-ilookup-tests (soft-cache-factory small-map)))
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
        (is (and r (= :bar (.get ^SoftReference r))))
        (.clear ^SoftReference r)
        (.enqueue ^SoftReference r)
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
        (.enqueue ^SoftReference @ref)
        (evict cache :foo)))))

(deftest test-equiv
  (is (= (fifo-cache-factory {:a 1 :c 3} :threshold 3)
         (fifo-cache-factory {:a 1 :c 3} :threshold 3))))

(deftest test-persistent-cons
  (is (let [starts-with-a (fifo-cache-factory {:a 1} :threshold 3)]
        (= (fifo-cache-factory {:a 1 :c 3} :threshold 3)
           (conj starts-with-a [:c 3])
           (conj starts-with-a {:c 3})))))

(deftest test-cache-iterable
  (let [c (fifo-cache-factory {:a 1 :b 2} :threshold 10)]
    (is (= #{:a :b} (set (iterator-seq (.iterator (keys c))))))))

(deftest test-satisfies-protocol
  (is (satisfies? CacheProtocol (basic-cache-factory {:a 1})))
  (is (satisfies? CacheProtocol (fifo-cache-factory {:a 1} :threshold 2)))
  (is (satisfies? CacheProtocol (lru-cache-factory {:a 1} :threshold 2)))
  (is (satisfies? CacheProtocol (ttl-cache-factory {:a 1} :ttl 5000)))
  (is (satisfies? CacheProtocol (lu-cache-factory {:a 1} :threshold 2)))
  (is (satisfies? CacheProtocol (lirs-cache-factory {:a 1})))
  (is (satisfies? CacheProtocol (soft-cache-factory {:a 1}))))
