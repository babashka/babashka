(ns linked.map-test
  (:require [linked.core :as linked]
            [clojure.test :refer :all]))

(deftest equality
  (let [empty-map (linked/map)
        one-item (assoc empty-map 1 2)]
    (testing "Basic symmetric equality"
      (is (= {} empty-map))
      (is (= empty-map {}))
      (is (= {1 2} one-item))
      (is (= one-item {1 2})))
    (testing "Order-insensitive comparisons"
      (let [one-way (into empty-map {1 2 3 4})
            other-way (into empty-map {3 4 1 2})
            unsorted {1 2 3 4}]
        (is (= one-way other-way))
        (is (= one-way unsorted))
        (is (= other-way unsorted))))
    (testing "Hash code sanity"
      (is (integer? (hash one-item)))
      (is (= (hash {1 2}) (hash one-item))))
    (testing "Does not blow up when give something different"
      (is (not= one-item 'baz))
      (is (not= 'baz one-item)))
    (testing "nil values don't break .equiv"
      (is (not= (linked/map :x nil) {:y 0})))))

(deftest ordering
  (let [values [[:first 10]
                [:second 20]
                [:third 30]]
        m (into (linked/map) values)]
    (testing "Seq behaves like on a seq of vectors"
      (is (= (seq values) (seq m))))
    (testing "New values get added at the end"
      (let [entry [:fourth 40]]
        (is (= (seq (conj values entry))
               (seq (conj m entry))))))
    (testing "Changing old mappings leaves them at the same location"
      (let [vec-index [1]
            vec-key (conj vec-index 1)
            map-key (get-in values (conj vec-index 0))
            new-value 5]
        (is (= (seq (assoc-in values vec-key new-value))
               (seq (assoc m map-key new-value))))))
    (testing "Large number of keys still sorted"
      (let [kvs (for [n (range 5000)]
                  [(str n) n])
            ordered (into m kvs)]
        (= (seq kvs) (seq ordered))))))

(deftest reversing
  (let [source (vec (for [n (range 10)]
                      [n n]))
        m (into (sorted-map) source)]
    (is (= (rseq m) (rseq source)))))

(deftest map-features
  (let [m (linked/map :a 1 :b 2 :c 3)]
    (testing "Keyword lookup"
      (is (= 1 (:a m))))
    (testing "Sequence views"
      (is (= [:a :b :c] (keys m)))
      (is (= [1 2 3] (vals m))))
    (testing "IFn support"
      (is (= 2 (m :b)))
      (is (= 'not-here (m :nothing 'not-here)))
      (is (= nil ((linked/map :x nil) :x 'not-here))))
    (testing "Get out Map.Entry"
      (is (= [:a 1] (find m :a))))
    (testing "Get out Map.Entry with falsy value"
      (is (= [:a nil] (find (linked/map :a nil) :a))))
    (testing "Ordered dissoc"
      (let [m (dissoc m :b)]
        (is (= [:a :c] (keys m)))
        (is (= [1 3] (vals m)))))
    (testing "Empty equality"
      (let [m (dissoc m :b :a :c)]
        (is (= (linked/map) m))))
    (testing "Can conj a map"
      (is (= {:a 1 :b 2 :c 3 :d 4} (conj m {:d 4}))))
    (testing "(conj m nil) returns m"
      (are [x] (= m x)
           (conj m nil)
           (merge m ())
           (into m ())))
    (testing "meta support"
      (is (= {'a 'b} (meta (with-meta m {'a 'b})))))))

(deftest object-features
  (let [m (linked/map 'a 1 :b 2)]
    (is (= "{a 1, :b 2}" (str m)))))

;; print-and-read-ordered skipped: print-method is registered for LinkedMap type,
;; but in babashka the deftype produces a SciMap, so custom tagged printing doesn't work.

(deftest map-entry-test
  (is (map-entry? (first (linked/map 1 2)))))
