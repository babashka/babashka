(ns clojure.math.test-combinatorics
  (:require [clojure.math.combinatorics :refer 
             [subsets combinations selections permutations cartesian-product
              partitions nth-permutation permutation-index drop-permutations
              permuted-combinations
              count-permutations count-combinations count-subsets 
              nth-subset nth-combination]]
            [clojure.test :refer [deftest is are testing run-tests]]))

(deftest test-permuted-combinations
  (are [x y] (= x y)
    (permuted-combinations [1 2 3] 2) '([1 2] [2 1] [1 3] [3 1] [2 3] [3 2])
    (permuted-combinations [1 2 2] 2) '([1 2] [2 1] [2 2])))

(deftest test-combinations
  (are [x y] (= x y)
    (combinations [1 2 3] 2) '((1 2) (1 3) (2 3))))

(defn old-subsets [l]
  (map (partial map deref) (subsets (map atom l)))) 

(deftest test-subsets
  (are [x y] (= x y)
       (subsets []) '(())
       (subsets [1 2 3]) '(() (1) (2) (3) (1 2) (1 3) (2 3) (1 2 3))
       (subsets [3 2 1]) '(() (3) (2) (1) (3 2) (3 1) (2 1) (3 2 1))
       (subsets [1 2 3 4]) '(() (1) (2) (3) (4) (1 2) (1 3) (1 4) (2 3) (2 4) (3 4) (1 2 3) (1 2 4) (1 3 4) (2 3 4) (1 2 3 4))
       (subsets [1 1 2]) (subsets [1 2 1])
       (subsets [1 3 2 3]) (subsets [1 3 3 2]))  
  (are [x] (and (= (subsets x) (distinct (old-subsets x)))
                (= (count (subsets x)) (count-subsets x)))
       []
       [1 2 3 4]
       [1 1 2 2 2 3]
       [1 1 1 2 2 3]
       [1 1 1 1 1]
       [1 2 2 3 3 3]
       [1 1 2 2 3 3]
       [1 1 1 2 2 2]
       [1 2 2 2 3 3]
       [1 1 1 1 2 2]
       [1 2 3 3 4 4]
       [1 1 2 3 3 4]))

(deftest test-nth-combination
  (are [x]
       (every?
         (fn [t]
           (and (= (count-combinations x t) (count (combinations x t)))
                (let [c (count-combinations x t)]
                  (= (for [i (range c)] (nth-combination x t i))
                     (combinations x t)))))
         (range (inc (count x))))
       [1 2 3 4]
       []
       [1 2 2 3 3 3]
       [1 1 1 2 2 3]
       [1 2 2 2 3 3]
       [1 1 1 2 3 3]
       [1 1 2 3 3 3]
       [1 2 3 1 2 1]
       [\a \b \c]
       [\a \b \c \a \b \c]
       ))

(deftest test-nth-subset
  (are [x]
       (let [c (count-subsets x)]
         (= (for [i (range c)] (nth-subset x i))
            (subsets x)))
       [1 2 3 4]
       []
       [1 2 2 3 3 3]
       [1 1 1 2 2 3]
       [1 2 2 2 3 3]
       [1 1 1 2 3 3]
       [1 1 2 3 3 3]
       [1 2 3 1 2 1]
       [\a \b \c]
       [\a \b \c \a \b \c]))

(deftest test-cartesian-product
  (are [x y] (= x y)
       (cartesian-product [1 2] [3 4]) '((1 3) (1 4) (2 3) (2 4))))

(deftest test-selections
  (are [x y] (= x y)
       (selections [1 2] 3) '((1 1 1) (1 1 2) (1 2 1) (1 2 2) (2 1 1) (2 1 2) (2 2 1) (2 2 2))))

(def lex-permutations @#'clojure.math.combinatorics/lex-permutations)

(deftest test-permutations
  (are [x y] (= x y)
       (permutations [1 2 3]) '((1 2 3) (1 3 2) (2 1 3) (2 3 1) (3 1 2) (3 2 1))
       (permutations [2 3 1]) '((2 3 1) (2 1 3) (3 2 1) (3 1 2) (1 2 3) (1 3 2))
       (permutations [1 1 2]) (lex-permutations [1 1 2])
       (permutations [:a :b]) [[:a :b] [:b :a]]
       (permutations [2 1 1]) [[2 1 1] [1 2 1] [1 1 2]]))

(deftest test-lex-permutations
  (are [x y] (= x y)
       (lex-permutations [1 1 2]) '([1 1 2] [1 2 1] [2 1 1])))

(def sorted-numbers? @#'clojure.math.combinatorics/sorted-numbers?)

(deftest test-sorted-numbers?
  (are [x y] (= x y)
       (sorted-numbers? [1 2 3]) true
       (sorted-numbers? [1 1 2]) true
       (sorted-numbers? []) true
       (sorted-numbers? [1 4 2]) false
       (sorted-numbers? [1 :a 2]) false))

(def factorial-numbers @#'clojure.math.combinatorics/factorial-numbers)

(deftest test-factorial-numbers
  (are [x y] (= (factorial-numbers x) y)
       463 [3 4 1 0 1 0]
       0 []
       1 [1 0]
       2 [1 0 0]))

(def nth-permutation-distinct @#'clojure.math.combinatorics/nth-permutation-distinct)

(deftest test-nth-permutation-distinct
  (let [perms (permutations (range 4))]
    (doseq [i (range 24)]
      (is (= (nth perms i) (nth-permutation-distinct (range 4) i))))))

(def nth-permutation-duplicates  @#'clojure.math.combinatorics/nth-permutation-duplicates)

(deftest test-nth-permutation-duplicates
  (let [perms (permutations [1 1 2 2 2 3])]
    (doseq [i (range 60)]
      (is (= (nth perms i) (nth-permutation-duplicates [1 1 2 2 2 3] i))))))

(deftest test-count-permutations
  (are [x] (= (count-permutations x) (count (permutations x)))
       (range 4)
       [1 1 2]
       [1 1 2 2]
       [1 1 1 2 2 3]))

(deftest test-nth-permutation
  (let [sortedDistinctNumbers (range 4)
        sortedDuplicateNumbers [1 1 1 2 3 3]
        distinctChars [\a \b \c \d]
        duplicates [\a \a \b \c \c]
        duplicates2 [1 3 1 2 1 2]]
    (doseq [collection [sortedDistinctNumbers 
                        sortedDuplicateNumbers
                        distinctChars
                        duplicates
                        duplicates2],
            :let [perms (permutations collection)
                  c (count perms)]
            n (range c)]
      (is (= (nth perms n) (nth-permutation collection n)))
      (is (= c (count-permutations collection))))))

(deftest test-drop-permutations
  (doseq [x [[1 2 3]
             [1 1 2]
             [\a \b \c]
             [\a \a \b \c \c]
             [1 3 1 2 1 2]]
          :let [c (count-permutations x)]
          i (range c)]
    (is (= (drop-permutations x i) (drop i (permutations x))))))

(deftest test-permutation-index
  (let [sortedDistinctNumbers (range 4)
        sortedDuplicateNumbers [1 1 1 2 3 3]
        distinctChars [\a \b \c \d]
        duplicates [\a \a \b \c \c]
        duplicates2 [1 3 1 2 1 2]]
    (doseq [collection [sortedDistinctNumbers 
                        sortedDuplicateNumbers
                        distinctChars
                        duplicates
                        duplicates2],
            perm (permutations collection)]
      (is (= (nth-permutation (sort collection) (permutation-index perm))
             perm)))))
            
(deftest test-partitions
  (do (are [x y] (= x y)
           (partitions [1 2 3]) '(([1 2 3]) ([1 2] [3]) ([1 3] [2]) ([1] [2 3]) ([1] [2] [3]))
           (partitions [1 2]) '(([1 2]) ([1] [2]))
           (partitions [1]) '(([1]))
           (partitions []) '(())
           (partitions nil) '(())
           (partitions [1 1 1]) '(([1 1 1]) ([1 1] [1]) ([1] [1] [1]))
           (partitions [1 1 2]) '(([1 1 2]) ([1 1] [2]) ([1 2] [1]) ([1] [1] [2])))
    (doseq [num-items (range 0 4)
            lo (range -1 (+ 2 num-items))
            hi (range -1 (+ 2 num-items))]
      (is (= (partitions (range num-items) :min lo :max hi)
             (filter #(<= lo (count %) hi) (partitions (range num-items)))))) ; tests partitions-H
    (doseq [num-items (range 2 4)
            lo (range -1 (+ 2 num-items))
            hi (range -1 (+ 2 num-items))]
      (is (= (partitions (cons 0 (range (dec num-items))) :min lo :max hi)
             (filter #(<= lo (count %) hi) (partitions (cons 0 (range (dec num-items)))))))))) ; tests partitions-M
