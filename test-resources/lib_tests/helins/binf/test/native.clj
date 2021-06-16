;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.


(ns helins.binf.test.native

  ""

  {:author "Adam Helinski"}

  (:require [clojure.test       :as t]
            [helins.binf.native :as binf.native]))


;;;;;;;;;;


(def n-byte
     64)



(def ptr
     (binf.native/alloc n-byte))

;;;;;


(t/deftest copy

  (let [ptr (binf.native/alloc 64)]

    (binf.native/w-b64 ptr
                       42)
    (binf.native/copy (+ ptr
                         8)
                      ptr
                      8)
    (t/is (= 42
             (binf.native/r-b64 (+ ptr
                                   8)))
          "From low address to high")

    (binf.native/w-b64 (+ ptr
                          8)
                       100)
    (binf.native/copy ptr
                      (+ ptr
                         8)
                      8)
    (t/is (= 100
             (binf.native/r-b64 ptr))
          "From high address to low")

    (binf.native/copy (inc ptr)
                      ptr
                      24)
    (t/is (= 100
             (binf.native/r-b64 (inc ptr)))
          "No corruption when dest address overlaps src address")))



(t/deftest free

  (t/is (nil? (binf.native/free (binf.native/alloc 4)))))



(t/deftest realloc

  (t/is (not (zero? (binf.native/realloc (binf.native/alloc 4)
                                         8)))))



(t/deftest rw

  (t/is (= -42
           (do
             (binf.native/w-b8 ptr
                               -42)
             (binf.native/r-i8 ptr))
           (do
             (binf.native/w-b16 ptr
                                -42)
             (binf.native/r-i16 ptr))
           (do
             (binf.native/w-b32 ptr
                                -42)
             (binf.native/r-i32 ptr))
           (do
             (binf.native/w-b64 ptr
                                -42)
             (binf.native/r-b64 ptr)))
        "Signed")

  (t/is (= 42
           (do
             (binf.native/w-b8 ptr
                               42)
             (binf.native/r-u8 ptr))
           (do
             (binf.native/w-b16 ptr
                                42)
             (binf.native/r-u16 ptr))
           (do
             (binf.native/w-b32 ptr
                                42)
             (binf.native/r-u32 ptr))
           (do
             (binf.native/w-b64 ptr
                                42)
             (binf.native/r-b64 ptr)))
        "Unsigned")

  (t/is (= (float 42.24)
           (do
             (binf.native/w-f32 ptr
                                42.24)
             (binf.native/r-f32 ptr)))
        "f32")

  (t/is (= 42.24
           (do
             (binf.native/w-f64 ptr
                                42.24)
             (binf.native/r-f64 ptr)))
        "f64")

  (t/is (= 0xffffffff
           (do
             (binf.native/w-ptr ptr
                                0xffffffff)
             (binf.native/r-ptr ptr)))
        "ptr"))
