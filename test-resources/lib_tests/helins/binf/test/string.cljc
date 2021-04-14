;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.


(ns helins.binf.test.string

  ""

  {:author "Adam Helinski"}

  (:require [clojure.test                    :as t]
            [clojure.test.check.clojure-test :as tc.ct]
            [clojure.test.check.generators   :as tc.gen]
            [clojure.test.check.properties   :as tc.prop]
            [helins.binf.string              :as binf.string]))


;;;;;;;;;;


(def string
     "²é&\"'(§è!çà)-aertyuiopqsdfhgklmwcvbnùµ,;:=")



(t/deftest main

  (t/is (= string
           (-> string
               binf.string/encode
               binf.string/decode))))



(tc.ct/defspec gen

  (tc.prop/for-all [string tc.gen/string]
    (= string
       (-> string
           binf.string/encode
           binf.string/decode))))
