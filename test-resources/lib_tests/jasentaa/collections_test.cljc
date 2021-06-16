(ns jasentaa.collections-test
  (:require
   #?(:clj  [clojure.test :refer :all]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [jasentaa.collections :refer [join]]
   [jasentaa.position :refer [augment-location]]))

(deftest check-join-on-lists
  (is (= [1 2] (join 1 2)))
  (is (= [3 4] (join [3] 4)))
  (is (= [5 6] (join 5 [6])))
  (is (= [7 8] (join [7] [8])))
  (is (= [9]   (join 9 nil)))
  (is (= [0]   (join nil 0)))
  (is (= []    (join nil nil))))

(deftest check-join-on-records
  (let [[a b] (augment-location "ab")]
    (is (= [a]   (join a nil)))
    (is (= [b]   (join nil b)))
    (is (= [a b] (join a b)))))

(deftest check-join-on-strings
  (is (= "ab" (join "a" "b")))
  (is (= "a"  (join "a" nil)))
  (is (= "b"  (join nil "b"))))
