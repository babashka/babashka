;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.


(ns helins.binf.test.float

  {:author "Adam Helins"}

  (:require [clojure.test.check.clojure-test :as tc.ct]
            [clojure.test.check.generators   :as tc.gen]
            [clojure.test.check.properties   :as tc.prop]
            [helins.binf.float               :as binf.float]))


;;;;;;;;;;


(defn nan?

  ""

  [x]

  #?(:clj  (Double/isNaN x)
     :cljs (js/isNaN x)))



(defn f=

  ""

  [x-1 x-2]

  (if (nan? x-1)
    (nan? x-2)
    (= x-1
       x-2)))


;;;;;;;;;;


#?(:clj (tc.ct/defspec f32

  (tc.prop/for-all [x (tc.gen/fmap unchecked-float
                                   tc.gen/double)]
    (f= x
        (binf.float/from-b32 (binf.float/b32 x))))))



(tc.ct/defspec f64

  (tc.prop/for-all [x tc.gen/double]
    (f= x
        (binf.float/from-b64 (binf.float/b64 x)))))
