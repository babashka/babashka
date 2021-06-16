;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.


(ns helins.binf.test.cabi

  {:author "Adam Helins"}

  (:require [clojure.test     :as t]
            [helins.binf.cabi :as binf.cabi])
  (:refer-clojure :exclude [array]))


;;;;;;;;;;


(def w32
     4)



(def w64
     8)



(def env32
     (binf.cabi/env w32))



(def env64
     (binf.cabi/env w64))



(defn member

  ""

  [f-member offset env]

  (assoc (f-member env)
         :binf.cabi/offset
         offset))


;;;;;;;;;; Enums


(t/deftest enum

  (t/is (= {:binf.cabi/align          w32
            :binf.cabi/n-byte         4
            :binf.cabi/type           :enum
            :binf.cabi.enum/constant+ {:a 0
                                       :b 1
                                       :c 1000
                                       :d 1001
                                       :e 42
                                       :f 43}
            :binf.cabi.enum/type      :foo}
           ((binf.cabi/enum :foo
                            [:a
                             :b
                             [:c 1000]
                             :d
                             [:e 42]
                             :f])
            env64))))


;;;;;;;;;; Unnested structs


(t/deftest struct-unnested


  (t/is (= {:binf.cabi/align          w32
            :binf.cabi/n-byte         12
            :binf.cabi/type           :struct
            :binf.cabi.struct/layout  [:a
                                       :b
                                       :c
                                       :d]
            :binf.cabi.struct/member+ {:a (member binf.cabi/u8
                                                  0
                                                  env32)
                                       :b (member binf.cabi/i16
                                                  2
                                                  env32)
                                       :c (member binf.cabi/u32
                                                  4
                                                  env32)
                                       :d (member binf.cabi/i8
                                                  8
                                                  env32)}
            :binf.cabi.struct/type    :foo}
           ((binf.cabi/struct :foo
                              [[:a binf.cabi/u8]
                               [:b binf.cabi/i16] 
                               [:c binf.cabi/u32] 
                               [:d binf.cabi/i8]])
            env32)))


  (t/is (= {:binf.cabi/align          w64
            :binf.cabi/n-byte         24
            :binf.cabi/type           :struct
            :binf.cabi.struct/layout  [:a
                                       :b
                                       :c]
            :binf.cabi.struct/member+ {:a (member binf.cabi/u8
                                                  0
                                                  env64)
                                       :b (member binf.cabi/f64
                                                  8
                                                  env64)
                                       :c (member binf.cabi/i16
                                                  16
                                                  env64)}
            :binf.cabi.struct/type    :foo}
           ((binf.cabi/struct :foo
                               [[:a binf.cabi/u8]
                                [:b binf.cabi/f64]
                                [:c binf.cabi/i16]])
            env64)))


  (t/is (= {:binf.cabi/align          w32
            :binf.cabi/n-byte         16
            :binf.cabi/type           :struct
            :binf.cabi.struct/layout  [:a
                                       :b
                                       :c
                                       :d]
            :binf.cabi.struct/member+ {:a (member binf.cabi/bool
                                                  0
                                                  env32)
                                       :b (member binf.cabi/u16
                                                  2
                                                  env32)
                                       :c (member binf.cabi/i64
                                                  4
                                                  env32)
                                       :d (member binf.cabi/u8
                                                  12
                                                  env32)}
            :binf.cabi.struct/type    :foo}
           ((binf.cabi/struct :foo
                              [[:a binf.cabi/bool]
                               [:b binf.cabi/u16]
                               [:c binf.cabi/i64]
                               [:d binf.cabi/u8]])
            env32))))


;;;;;;;;;; Pointers


(t/deftest ptr

  (t/is (= {:binf.cabi/align          4
            :binf.cabi/n-byte         w32
            :binf.cabi/type           :ptr
            :binf.cabi.pointer/target ((binf.cabi/struct :foo
                                                         [[:a binf.cabi/u64]])
                                       env32)}
           ((binf.cabi/ptr (binf.cabi/struct :foo
                                             [[:a binf.cabi/u64]]))
            (assoc env32
                   :binf.cabi.pointer/n-byte
                   w32)))))

;;;;;;;;;; Arrays


(t/deftest array-primitive

  (t/is (= {:binf.cabi/align           4
            :binf.cabi/n-byte          40
            :binf.cabi/type            :array
            :binf.cabi.array/element   (binf.cabi/f32 env64)
            :binf.cabi.array/n-element 10}
           ((binf.cabi/array binf.cabi/f32
                             10)
            env64))
        "1D")

  (t/is (= {:binf.cabi/align           4
            :binf.cabi/n-byte          160
            :binf.cabi/type            :array
            :binf.cabi.array/element   {:binf.cabi/align           4
                                        :binf.cabi/n-byte          80
                                        :binf.cabi/type            :array
                                        :binf.cabi.array/element   (binf.cabi/f64 env32)
                                        :binf.cabi.array/n-element 10}
            :binf.cabi.array/n-element 2}
           ((-> binf.cabi/f64
                (binf.cabi/array 10)
                (binf.cabi/array 2))
            env32))
        "2D"))



(t/deftest struct-with-array


  (t/is (= {:binf.cabi/align          2
            :binf.cabi/n-byte         22
            :binf.cabi/type           :struct
            :binf.cabi.struct/member+ {:a (member binf.cabi/u8
                                                  0
                                                  env64)
                                       :b (member (fn [env]
                                                    ((binf.cabi/array binf.cabi/u16
                                                                      10)
                                                     env))
                                                  2
                                                  env64)}
            :binf.cabi.struct/layout  [:a
                                       :b]
            :binf.cabi.struct/type    :foo}
           ((binf.cabi/struct :foo
                              [[:a binf.cabi/u8]
                               [:b (binf.cabi/array binf.cabi/u16
                                                    10)]])
            env64))
        "1D")


  (t/is (= {:binf.cabi/align          2
            :binf.cabi/n-byte         102
            :binf.cabi/type           :struct
            :binf.cabi.struct/layout  [:a
                                       :b]
            :binf.cabi.struct/member+ {:a (member binf.cabi/bool
                                                  0
                                                  env64)
                                       :b (member (fn [env]
                                                    ((-> binf.cabi/u16
                                                         (binf.cabi/array 10)
                                                         (binf.cabi/array 5))
                                                     env))
                                                  2
                                                  env64)}
            :binf.cabi.struct/type    :foo}
           ((binf.cabi/struct :foo
                              [[:a binf.cabi/bool]
                               [:b (-> binf.cabi/u16
                                       (binf.cabi/array 10)
                                       (binf.cabi/array 5))]])
            env64))
        "2D"))



(t/deftest array-struct

  (t/is (= {:binf.cabi/align           4
            :binf.cabi/n-byte          40
            :binf.cabi/type            :array
            :binf.cabi.array/element   ((binf.cabi/struct :foo
                                                          [[:a binf.cabi/u32]])
                                        env64)
            :binf.cabi.array/n-element 10}
           ((binf.cabi/array (binf.cabi/struct :foo
                                               [[:a binf.cabi/u32]])
                             10)
            env64))))


;;;;;;;;;; Nested structs


(def struct-inner
     (binf.cabi/struct :bar
                       [[:c binf.cabi/i8]
                        [:d binf.cabi/f64]]))



(t/deftest struct-nested

  (t/is (= {:binf.cabi/align          w32
            :binf.cabi/n-byte         16
            :binf.cabi/type           :struct
            :binf.cabi.struct/layout  [:a
                                       :b]
            :binf.cabi.struct/member+ {:a (member binf.cabi/u16
                                                  0
                                                  env32)
                                       :b (member struct-inner
                                                  4
                                                  env32)}
            :binf.cabi.struct/type    :foo}
           ((binf.cabi/struct :foo
                              [[:a binf.cabi/u16]
                               [:b struct-inner]])
            env32))))


;;;;;;;;;; Unions


(t/deftest union

  (t/is (= {:binf.cabi/align         8
            :binf.cabi/n-byte        16
            :binf.cabi/type          :union
            :binf.cabi.union/member+ {:a (binf.cabi/i8 env64)
                                      :b ((binf.cabi/struct :bar
                                                            [[:c binf.cabi/u16]
                                                             [:d binf.cabi/f64]])
                                          env64)}
            :binf.cabi.union/type    :foo}
           ((binf.cabi/union :foo
                             {:a binf.cabi/i8
                              :b (binf.cabi/struct :bar
                                                   [[:c binf.cabi/u16]
                                                    [:d binf.cabi/f64]])})
            env64))))
