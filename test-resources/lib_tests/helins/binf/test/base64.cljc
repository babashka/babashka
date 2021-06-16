;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.


(ns helins.binf.test.base64

  {:author "Adam Helins"}

  (:require [clojure.test                    :as t]
            [clojure.test.check.clojure-test :as tc.ct]
            [clojure.test.check.generators   :as tc.gen]
            [clojure.test.check.properties   :as tc.prop]
            [helins.binf                     :as binf]
            [helins.binf.base64              :as binf.base64]
            [helins.binf.buffer              :as binf.buffer]
            [helins.binf.gen                 :as binf.gen]))


;;;;;;;;;;


(t/deftest main

  (let [buffer (binf.buffer/alloc 64)
        view   (binf/view buffer)
        #?@(:cljs [buffer-shared (binf.buffer/alloc-shared 64)
                   view-shared   (binf/view buffer-shared)])]
    (dotimes [i 64]
      (binf/wr-b8 view
                  i)
      #?(:cljs (binf/wr-b8 view-shared
                           i)))
    (t/is (= (seq buffer)
             (seq (-> buffer
                      binf.base64/encode
                      binf.base64/decode
                      binf/backing-buffer))
             #?(:cljs (seq (-> buffer-shared
                               binf.base64/encode
                               (binf.base64/decode binf.buffer/alloc-shared)
                               binf/backing-buffer))))
          "Without offset nor lenght")
    (t/is (= (drop 5
                   (seq buffer))
             (seq (-> buffer
                      (binf.base64/encode 5)
                      binf.base64/decode
                      binf/backing-buffer))
             #?(:cljs (seq (-> buffer-shared
                               (binf.base64/encode 5)
                               (binf.base64/decode binf.buffer/alloc-shared)
                               binf/backing-buffer))))
          "With offset without length")
    (t/is (= (->> (seq buffer)
                  (drop 5)
                  (take 20))
             (seq (-> buffer
                      (binf.base64/encode 5
                                          20)
                      binf.base64/decode
                      binf/backing-buffer))
             #?(:cljs (seq (-> buffer-shared
                               (binf.base64/encode 5
                                                   20)
                               (binf.base64/decode binf.buffer/alloc-shared)
                               binf/backing-buffer))))
          "With offset and length")))



(tc.ct/defspec gen

  (tc.prop/for-all [buffer binf.gen/buffer]
    (= (seq buffer)
       (let [view (-> buffer
                      binf.base64/encode
                      binf.base64/decode)]
         (seq (binf/rr-buffer view
                              (binf/limit view)))))))
