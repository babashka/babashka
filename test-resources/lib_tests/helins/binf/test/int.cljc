;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.


(ns helins.binf.test.int

  {:author "Adam Helins"}

  (:require [clojure.test      :as t]
            [helins.binf.int   :as binf.int]
            [helins.binf.int64 :as binf.int64]))


;;;;;;;;;;


(t/deftest casting

  (t/are [n-bit fi fu]
         (let [value (dec (binf.int/from-float (Math/pow 2
                                                         n-bit)))]
           (t/is (= value
                    (-> value
                        fu
                        fi
                        fu
                        fi
                        fu))))
    8  binf.int/i8  binf.int/u8
    16 binf.int/i16 binf.int/u16
    32 binf.int/i32 binf.int/u32))



(t/deftest byte-combining

  (t/is (= 0x1122
           (binf.int/i16 0x11
                         0x22)
           (binf.int/u16 0x11
                         0x22))
        "16-bits")

  (t/is (= 0x11223344
           (binf.int/i32 0x11
                         0x22
                         0x33
                         0x44)
           (binf.int/i32 0x1122
                         0x3344)
           (binf.int/u32 0x11
                         0x22
                         0x33
                         0x44)
           (binf.int/u32 0x1122
                         0x3344))
        "32-bit")

  (t/is (= (binf.int64/i* 0x1122334455667788)
           (binf.int/i64 0x11
                         0x22
                         0x33
                         0x44
                         0x55
                         0x66
                         0x77
                         0x88)
           (binf.int/i64 0x1122
                         0x3344
                         0x5566
                         0x7788)
           (binf.int/i64 0x11223344
                         0x55667788))
        "Signed 64-bit")

  (t/is (= (binf.int64/u* 0x1122334455667788)
           (binf.int/u64 0x11
                         0x22
                         0x33
                         0x44
                         0x55
                         0x66
                         0x77
                         0x88)
           (binf.int/u64 0x1122
                         0x3344
                         0x5566
                         0x7788)
           (binf.int/u64 0x11223344
                         0x55667788))
        "Unsigned 64-bit"))
