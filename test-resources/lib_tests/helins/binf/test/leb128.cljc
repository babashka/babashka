;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.


(ns helins.binf.test.leb128

  {:author "Adam Helins"}

  (:require [clojure.test                    :as t]
            [clojure.test.check.clojure-test :as tc.ct]
            [clojure.test.check.properties   :as tc.prop]
            [helins.binf                     :as binf]
            [helins.binf.buffer              :as binf.buffer]
            [helins.binf.gen                 :as binf.gen]
            [helins.binf.int64               :as binf.int64]
            [helins.binf.leb128              :as binf.leb128]))


;;;;;;;;;; int32


(t/deftest u32

  (let [v (binf/view (binf.buffer/alloc 32))]

    (-> v
        (binf/seek 0)
        (binf.leb128/wr-u32 0))

    (t/is (= 1
             (binf/position v)
             (binf.leb128/n-byte-u32 0)))

    (t/is (= 0
             (-> v
                 (binf/seek 0)
                 (binf.leb128/rr-u32))))


    (-> v
        (binf/seek 0)
        (binf.leb128/wr-u32 4294967295))

    (t/is (= (binf.leb128/n-byte-max 32)
             (binf/position v)
             (binf.leb128/n-byte-u32 4294967295)))

    (t/is (= 4294967295
             (-> v
                 (binf/seek 0)
                 (binf.leb128/rr-u32))))))



(t/deftest i32

  (let [v (binf/view (binf.buffer/alloc 32))]

    (-> v
        (binf/seek 0)
        (binf.leb128/wr-i32 0))

    (t/is (= 1
             (binf/position v)
             (binf.leb128/n-byte-i32 0)))

    (t/is (= 0
             (-> v
                 (binf/seek 0)
                 (binf.leb128/rr-i32))))

    (-> v
        (binf/seek 0)
        (binf.leb128/wr-i32 2147483647))

    (t/is (= (binf.leb128/n-byte-max 32)
             (binf/position v)
             (binf.leb128/n-byte-i32 2147483647)))

    (t/is (= 2147483647
             (-> v
                 (binf/seek 0)
                 (binf.leb128/rr-i32))))

    (-> v
        (binf/seek 0)
        (binf.leb128/wr-i32 -2147483648))

    (t/is (= (binf.leb128/n-byte-max 32)
             (binf/position v)
             (binf.leb128/n-byte-i32 -2147483648)))

    (t/is (= -2147483648
             (-> v
                 (binf/seek 0)
                 (binf.leb128/rr-i32))))

    (-> v
        (binf/seek 0)
        (binf.leb128/wr-i32 -42))

    (t/is (= 1
             (binf/position v)
             (binf.leb128/n-byte-i32 0)))

    (t/is (= -42
             (-> v
                 (binf/seek 0)
                 (binf.leb128/rr-i32))))

    (-> v
        (binf/seek 0)
        (binf/wr-b8 0x7F))

    (t/is (= 1
             (binf/position v)
             (binf.leb128/n-byte-i32 0)))

    (t/is (= -1
             (-> v
                 (binf/seek 0)
                 (binf.leb128/rr-i32))))))


;;;;;;;;;; int64


(t/deftest u64

  (let [v (binf/view (binf.buffer/alloc 32))]

    (-> v
        (binf/seek 0)
        (binf.leb128/wr-u64 (binf.int64/u* 0)))

    (t/is (= 1
             (binf/position v)
             (binf.leb128/n-byte-u64 (binf.int64/u* 0))))

    (t/is (= (binf.int64/u* 0)
             (-> v
                 (binf/seek 0)
                 (binf.leb128/rr-u64))))

    (-> v
        (binf/seek 0)
        (binf.leb128/wr-u64 (binf.int64/u* 18446744073709551615)))

    (t/is (= (binf.leb128/n-byte-max 64)
             (binf/position v)
             (binf.leb128/n-byte-u64 (binf.int64/u* 18446744073709551615))))

    (t/is (= (binf.int64/u* 18446744073709551615)
             (-> v
                 (binf/seek 0)
                 (binf.leb128/rr-u64))))))



(t/deftest i64

  (let [v (binf/view (binf.buffer/alloc 32))]

    (-> v
        (binf/seek 0)
        (binf.leb128/wr-i64 (binf.int64/i* 0)))

    (t/is (= 1
             (binf/position v)
             (binf.leb128/n-byte-i64 (binf.int64/i* 0))))

    (t/is (= (binf.int64/i* 0)
             (-> v
                 (binf/seek 0)
                 (binf.leb128/rr-i64))))

    (-> v
        (binf/seek 0)
        (binf.leb128/wr-i64 (binf.int64/i* 9223372036854775807)))

    (t/is (= (binf.leb128/n-byte-max 64)
             (binf/position v)
             (binf.leb128/n-byte-i64 (binf.int64/i* 9223372036854775807))))

    (t/is (= (binf.int64/i* 9223372036854775807)
             (-> v
                 (binf/seek 0)
                 (binf.leb128/rr-i64))))

    (-> v
        (binf/seek 0)
        (binf.leb128/wr-i64 (binf.int64/i* -9223372036854775808)))

    (t/is (= (binf.leb128/n-byte-max 64)
             (binf/position v)
             (binf.leb128/n-byte-i64 (binf.int64/i* -9223372036854775808))))

    (t/is (= (binf.int64/i* -9223372036854775808)
             (-> v
                 (binf/seek 0)
                 (binf.leb128/rr-i64))))

    (-> v
        (binf/seek 0)
        (binf.leb128/wr-i64 (binf.int64/i* -42)))

    (t/is (= 1
             (binf/position v)
             (binf.leb128/n-byte-i64 (binf.int64/i* -42))))

    (t/is (= (binf.int64/i* -42)
             (-> v
                 (binf/seek 0)
                 (binf.leb128/rr-i64))))

    (-> v
        (binf/seek 0)
        (binf/wr-b8 0x7F))

    (t/is (= 1
             (binf/position v)
             (binf.leb128/n-byte-i64 (binf.int64/i* -1))))

    (t/is (= (binf.int64/i* -1)
             (-> v
                 (binf/seek 0)
                 (binf.leb128/rr-i64))))))


;;;;;;;;;; Generative testing


(def view-gen
     (-> (binf.leb128/n-byte-max 64)
         binf.buffer/alloc
         binf/view))


(tc.ct/defspec gen-i32

  (tc.prop/for-all [i32 binf.gen/i32]
    (= i32
       (-> view-gen
           (binf/seek 0)
           (binf.leb128/wr-i32 i32)
           (binf/seek 0)
           binf.leb128/rr-i32))))



(tc.ct/defspec gen-u32

  (tc.prop/for-all [u32 binf.gen/u32]
    (= u32
       (-> view-gen
           (binf/seek 0)
           (binf.leb128/wr-u32 u32)
           (binf/seek 0)
           binf.leb128/rr-u32))))



(tc.ct/defspec gen-i64

  (tc.prop/for-all [i64 binf.gen/i64]
    (= i64
       (-> view-gen
           (binf/seek 0)
           (binf.leb128/wr-i64 i64)
           (binf/seek 0)
           binf.leb128/rr-i64))))



(tc.ct/defspec gen-u64

  (tc.prop/for-all [u64 binf.gen/u64]
    (= u64
       (-> view-gen
           (binf/seek 0)
           (binf.leb128/wr-u64 u64)
           (binf/seek 0)
           binf.leb128/rr-u64))))
