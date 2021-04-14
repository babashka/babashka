;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.


(ns helins.binf.test.int64

  {:author "Adam Helins"}

  (:require [clojure.test      :as t]
            [helins.binf.int64 :as binf.int64])
  (:refer-clojure :exclude [bit-clear
                            bit-flip
                            bit-set
                            bit-test]))


;;;;;;;;;; Casting to ints <= 32-bits


(t/deftest casting-smaller

  (let [n (binf.int64/i* -42)]
    (t/is (= -42
             (binf.int64/i8  n)
             (binf.int64/i16 n)
             (binf.int64/i32 n))
          "Signed"))

  (let [n (binf.int64/u* 42)]
    (t/is (= 42
             (binf.int64/u8  n)
             (binf.int64/u16 n)
             (binf.int64/u32 n))
          "Unsigned")))


;;;;;;;;;; Bitwise operations from standard lib which does not work with js/BigInt


(t/deftest bit-clear

  (t/is (zero? (binf.int64/u32 (binf.int64/bit-clear (binf.int64/u* 2r10)
                                                     (binf.int64/u* 1)))))

  (t/is (zero? (binf.int64/u32 (binf.int64/bit-clear (binf.int64/u* 0)
                                                     (binf.int64/u* 1))))))



(t/deftest bit-flip

  (t/is (zero? (binf.int64/u32 (binf.int64/bit-flip (binf.int64/u* 2r10)
                                                    (binf.int64/u* 1)))))

  (t/is (= (binf.int64/u* 2)
           (binf.int64/bit-flip (binf.int64/u* 2r00)
                                (binf.int64/u* 1)))))



(t/deftest bit-set

  (t/is (= (binf.int64/u* 2)
           (binf.int64/bit-set (binf.int64/u* 2r00)
                               (binf.int64/u* 1)))))



(t/deftest bit-test

  (t/is (true? (binf.int64/bit-test (binf.int64/u* 2r10)
                                    (binf.int64/u* 1))))

  (t/is (false? (binf.int64/bit-test (binf.int64/u* 0)
                                     (binf.int64/u* 1)))))


;;;;;;;;;; Unsigned logic tests


(def u64-max
     (binf.int64/u* 0xffffffffffffffff))



(def u64-min
     (binf.int64/u* 0))



(t/deftest u<

  (t/is (binf.int64/u< u64-min
                       u64-max))

  (t/is (false? (binf.int64/u< u64-max
                               u64-min)))

  (t/is (false? (binf.int64/u< u64-max
                               u64-max))))



(t/deftest u<=

  (t/is (binf.int64/u<= u64-min
                        u64-max))

  (t/is (false? (binf.int64/u<= u64-max
                                u64-min)))

  (t/is (binf.int64/u<= u64-max
                        u64-max)))



(t/deftest u>

  (t/is (binf.int64/u> u64-max
                       u64-min))

  (t/is (false? (binf.int64/u> u64-min
                               u64-max)))

  (t/is (false? (binf.int64/u> u64-max
                               u64-max))))



(t/deftest u>=

  (t/is (binf.int64/u>= u64-max
                        u64-min))

  (t/is (false? (binf.int64/u>= u64-min
                                u64-max)))

  (t/is (binf.int64/u>= u64-max
                        u64-max)))


;;;;;;;;;; Unsigned maths


(t/deftest udiv

  (t/is (= (binf.int64/u* 0x7fffffffffffffff)
           (binf.int64/udiv u64-max
                            (binf.int64/u* 2)))))



(t/deftest urem

  (t/is (= (binf.int64/u* 1)
           (binf.int64/urem (binf.int64/u* 10)
                            (binf.int64/u* 3)))))
