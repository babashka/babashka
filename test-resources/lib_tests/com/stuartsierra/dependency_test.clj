;; Copyright (c) Stuart Sierra, 2012-2015. All rights reserved. The use and
;; distribution terms for this software are covered by the Eclipse Public
;; License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which can be
;; found in the file epl-v10.html at the root of this distribution. By using
;; this software in any fashion, you are agreeing to be bound by the terms of
;; this license. You must not remove this notice, or any other, from this
;; software.

(ns com.stuartsierra.dependency-test
  (:require [clojure.test :refer [deftest is are]]
            [com.stuartsierra.dependency :refer :all]))

;; building a graph like:
;;
;;       :a
;;      / |
;;    :b  |
;;      \ |
;;       :c
;;        |
;;       :d
;;
(def g1 (-> (graph)
            (depend :b :a)   ; "B depends on A"
            (depend :c :b)   ; "C depends on B"
            (depend :c :a)   ; "C depends on A"
            (depend :d :c))) ; "D depends on C"

;;      'one    'five
;;        |       |
;;      'two      |
;;       / \      |
;;      /   \     |
;;     /     \   /
;; 'three   'four
;;    |      /
;;  'six    /
;;    |    /
;;    |   /
;;    |  /
;;  'seven
;;
(def g2 (-> (graph)
            (depend 'two   'one)
            (depend 'three 'two)
            (depend 'four  'two)
            (depend 'four  'five)
            (depend 'six   'three)
            (depend 'seven 'six)
            (depend 'seven 'four)))

;;               :level0
;;               / | |  \
;;          -----  | |   -----
;;         /       | |        \
;; :level1a :level1b :level1c :level1d
;;         \       | |        /
;;          -----  | |   -----
;;               \ | |  /
;;               :level2
;;               / | |  \
;;          -----  | |   -----
;;         /       | |        \
;; :level3a :level3b :level3c :level3d
;;         \       | |        /
;;          -----  | |   -----
;;               \ | |  /
;;               :level4
;;
;; ... and so on in a repeating pattern like that, up to :level26

(def g3 (-> (graph)
            (depend :level1a :level0)
            (depend :level1b :level0)
            (depend :level1c :level0)
            (depend :level1d :level0)
            (depend :level2 :level1a)
            (depend :level2 :level1b)
            (depend :level2 :level1c)
            (depend :level2 :level1d)

            (depend :level3a :level2)
            (depend :level3b :level2)
            (depend :level3c :level2)
            (depend :level3d :level2)
            (depend :level4 :level3a)
            (depend :level4 :level3b)
            (depend :level4 :level3c)
            (depend :level4 :level3d)

            (depend :level5a :level4)
            (depend :level5b :level4)
            (depend :level5c :level4)
            (depend :level5d :level4)
            (depend :level6 :level5a)
            (depend :level6 :level5b)
            (depend :level6 :level5c)
            (depend :level6 :level5d)

            (depend :level7a :level6)
            (depend :level7b :level6)
            (depend :level7c :level6)
            (depend :level7d :level6)
            (depend :level8 :level7a)
            (depend :level8 :level7b)
            (depend :level8 :level7c)
            (depend :level8 :level7d)

            (depend :level9a :level8)
            (depend :level9b :level8)
            (depend :level9c :level8)
            (depend :level9d :level8)
            (depend :level10 :level9a)
            (depend :level10 :level9b)
            (depend :level10 :level9c)
            (depend :level10 :level9d)

            (depend :level11a :level10)
            (depend :level11b :level10)
            (depend :level11c :level10)
            (depend :level11d :level10)
            (depend :level12 :level11a)
            (depend :level12 :level11b)
            (depend :level12 :level11c)
            (depend :level12 :level11d)

            (depend :level13a :level12)
            (depend :level13b :level12)
            (depend :level13c :level12)
            (depend :level13d :level12)
            (depend :level14 :level13a)
            (depend :level14 :level13b)
            (depend :level14 :level13c)
            (depend :level14 :level13d)

            (depend :level15a :level14)
            (depend :level15b :level14)
            (depend :level15c :level14)
            (depend :level15d :level14)
            (depend :level16 :level15a)
            (depend :level16 :level15b)
            (depend :level16 :level15c)
            (depend :level16 :level15d)

            (depend :level17a :level16)
            (depend :level17b :level16)
            (depend :level17c :level16)
            (depend :level17d :level16)
            (depend :level18 :level17a)
            (depend :level18 :level17b)
            (depend :level18 :level17c)
            (depend :level18 :level17d)

            (depend :level19a :level18)
            (depend :level19b :level18)
            (depend :level19c :level18)
            (depend :level19d :level18)
            (depend :level20 :level19a)
            (depend :level20 :level19b)
            (depend :level20 :level19c)
            (depend :level20 :level19d)

            (depend :level21a :level20)
            (depend :level21b :level20)
            (depend :level21c :level20)
            (depend :level21d :level20)
            (depend :level22 :level21a)
            (depend :level22 :level21b)
            (depend :level22 :level21c)
            (depend :level22 :level21d)

            (depend :level23a :level22)
            (depend :level23b :level22)
            (depend :level23c :level22)
            (depend :level23d :level22)
            (depend :level24 :level23a)
            (depend :level24 :level23b)
            (depend :level24 :level23c)
            (depend :level24 :level23d)

            (depend :level25a :level24)
            (depend :level25b :level24)
            (depend :level25c :level24)
            (depend :level25d :level24)
            (depend :level26 :level25a)
            (depend :level26 :level25b)
            (depend :level26 :level25c)
            (depend :level26 :level25d)))

(deftest t-transitive-dependencies
  (is (= #{:a :c :b}
         (transitive-dependencies g1 :d)))
  (is (= '#{two four six one five three}
         (transitive-dependencies g2 'seven))))

(deftest t-transitive-dependencies-deep
  (is (= #{:level0
           :level1a :level1b :level1c :level1d
           :level2
           :level3a :level3b :level3c :level3d
           :level4
           :level5a :level5b :level5c :level5d
           :level6
           :level7a :level7b :level7c :level7d
           :level8
           :level9a :level9b :level9c :level9d
           :level10
           :level11a :level11b :level11c :level11d
           :level12
           :level13a :level13b :level13c :level13d
           :level14
           :level15a :level15b :level15c :level15d
           :level16
           :level17a :level17b :level17c :level17d
           :level18
           :level19a :level19b :level19c :level19d
           :level20
           :level21a :level21b :level21c :level21d
           :level22
           :level23a :level23b :level23c :level23d}
         (transitive-dependencies g3 :level24)))
  (is (= #{:level0
           :level1a :level1b :level1c :level1d
           :level2
           :level3a :level3b :level3c :level3d
           :level4
           :level5a :level5b :level5c :level5d
           :level6
           :level7a :level7b :level7c :level7d
           :level8
           :level9a :level9b :level9c :level9d
           :level10
           :level11a :level11b :level11c :level11d
           :level12
           :level13a :level13b :level13c :level13d
           :level14
           :level15a :level15b :level15c :level15d
           :level16
           :level17a :level17b :level17c :level17d
           :level18
           :level19a :level19b :level19c :level19d
           :level20
           :level21a :level21b :level21c :level21d
           :level22
           :level23a :level23b :level23c :level23d
           :level24
           :level25a :level25b :level25c :level25d}
         (transitive-dependencies g3 :level26))))


(deftest t-transitive-dependents
  (is (= '#{four seven}
         (transitive-dependents g2 'five)))
  (is (= '#{four seven six three}
         (transitive-dependents g2 'two))))

(defn- before?
  "True if x comes before y in an ordered collection."
  [coll x y]
  (loop [[item & more] (seq coll)]
    (cond (nil? item) true  ; end of the seq
          (= x item) true  ; x comes first
          (= y item) false
          :else (recur more))))

(deftest t-before
  (is (true? (before? [:a :b :c :d] :a :b)))
  (is (true? (before? [:a :b :c :d] :b :c)))
  (is (false? (before? [:a :b :c :d] :d :c)))
  (is (false? (before? [:a :b :c :d] :c :a))))

(deftest t-topo-comparator-1
  (let [sorted (sort (topo-comparator g1) [:d :a :b :foo])]
    (are [x y] (before? sorted x y)
         :a :b
         :a :d
         :a :foo
         :b :d
         :b :foo
         :d :foo)))

(deftest t-topo-comparator-2
  (let [sorted (sort (topo-comparator g2) '[three seven nine eight five])]
    (are [x y] (before? sorted x y)
         'three 'seven
         'three 'eight
         'three 'nine
         'five  'eight
         'five  'nine
         'seven 'eight
         'seven 'nine)))

(deftest t-topo-sort
  (let [sorted (topo-sort g2)]
    (are [x y] (before? sorted x y)
         'one   'two
         'one   'three
         'one   'four
         'one   'six
         'one   'seven
         'two   'three
         'two   'four
         'two   'six
         'two   'seven
         'three 'six
         'three 'seven
         'four  'seven
         'five  'four
         'five  'seven
         'six   'seven)))

(deftest t-no-cycles
  (is (thrown? Exception
               (-> (graph)
                   (depend :a :b)
                   (depend :b :c)
                   (depend :c :a)))))

(deftest t-no-self-cycles
  (is (thrown? Exception
               (-> (graph)
                   (depend :a :b)
                   (depend :a :a)))))
