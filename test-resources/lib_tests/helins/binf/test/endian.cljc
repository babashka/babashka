;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.


(ns helins.binf.test.endian

  ""

  {:author "Adam Helinski"}

  (:require [clojure.test                    :as t]
            [clojure.test.check.clojure-test :as tc.ct]
            [clojure.test.check.properties   :as tc.prop]
            [helins.binf.int                 :as binf.int]
            [helins.binf.int64               :as binf.int64]
            [helins.binf.endian              :as binf.endian]
            [helins.binf.gen                 :as binf.gen]))


;;;;;;;;;;


(t/deftest main

  (t/is (= 0x01234
           (binf.endian/b16 0x3412))
        "16-bit")
  
  (t/is (= 0x11223344
           (binf.endian/b32 0x44332211))
        "32-bit")
  (t/is (= (binf.int64/u* 0x1122334455667788)
           (binf.endian/b64 (binf.int64/u* 0x8877665544332211)))
        "64-bit"))


;;;;;;;;;; Generative


(tc.ct/defspec b16

  (tc.prop/for-all [u16 binf.gen/u16]
    (= u16
       (-> u16
           binf.endian/b16
           binf.endian/b16
           binf.int/u16))))



(tc.ct/defspec b32

  (tc.prop/for-all [u32 binf.gen/u32]
    (= u32
       (-> u32
           binf.endian/b32
           binf.endian/b32
           binf.int/u32))))



(tc.ct/defspec b64

  (tc.prop/for-all [u64 binf.gen/u64]
    (= u64
       (-> u64
           binf.endian/b64
           binf.endian/b64
           binf.int64/u*))))

