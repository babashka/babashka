(ns cli-matic.utils-candidates-test
  (:require [clojure.test :refer :all])
  (:require [cli-matic.utils-candidates :refer [str-distance
                                                candidate-suggestions]]))

(defn abs [n] (max n (- n)))

(defn float=
  "Approximate float equality.

  Jeez, in each and every language I used in my life I
  had to write this. Sometimes I wonder which way thing are
  going.
   "
  [a b]
  (let [fa (float a)
        fb (float b)
        err 0.001]
    (> err (abs (- fa fb)))))

(deftest str-distance-test
  (are [s1 s2 d]
       (float= d (str-distance s1 s2))

    ; same string = 0
    "pippo" "pippo" 0

    ; one change
    "pippo" "Pippo" 0.20

    ; compute as prc of longest
    "pippox" "Pippo" 0.334

    ; nils?
    "xxx" nil 1

    ; both empty
    "" "" 0

    ; both nil
    nil nil 0))

(deftest candidate-suggestions-test

  (are [c t r]
       (= r (vec (candidate-suggestions c t 0.5)))

    ; only one
    ["foo" "bar" "baz" "buzz"] "baar" ["bar" "baz"]

    ;none
    ["foo" "bar" "baz" "buzz"] "zebra" []

    ; best comes first
    ["foo" "bara" "barrr" "buzz" "o"] "bar" ["bara" "barrr"]

    ;none found
    ["foo" "bara" "barrr" "buzz" "o"] "qaqaqa" []))
