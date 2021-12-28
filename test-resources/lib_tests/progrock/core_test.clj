(ns progrock.core-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [progrock.core :as pr]))

(deftest test-progress-bar
  (let [bar (pr/progress-bar 50)]
    (is (= (:total bar) 50))
    (is (= (:progress bar) 0))
    (is (not (:done? bar)))))

(deftest test-tick
  (let [bar (pr/progress-bar 50)]
    (is (= (-> bar pr/tick :progress) 1))
    (is (= (-> bar (pr/tick 16) :progress) 16))
    (is (= (-> bar (pr/tick 5) pr/tick :progress) 6))))

(deftest test-done
  (let [bar (pr/progress-bar 50)]
    (is (-> bar pr/done :done?))))

(deftest test-render
  (let [bar (pr/progress-bar 50)]
    (is (= (pr/render bar)
           " 0/50     0% [                                                  ]  ETA: --:--"))
    (is (= (pr/render (pr/tick bar 25))
           "25/50    50% [=========================                         ]  ETA: 00:00"))
    (is (= (pr/render (pr/tick bar 25) {:format "(:bar)", :length 10})
           "(=====     )"))
    (is (= (pr/render (pr/tick bar 25) {:format "[:bar]", :complete \#, :incomplete \-})
           "[#########################-------------------------]"))
    (is (= (pr/render (pr/progress-bar 0))
           "0/0     0% [                                                  ]  ETA: --:--"))))

(deftest test-print
  (let [bar (pr/progress-bar 50)]
    (is (= (with-out-str (pr/print bar))
           "\r 0/50     0% [                                                  ]  ETA: --:--"))
    (is (= (with-out-str (pr/print bar {:length 10}))
           "\r 0/50     0% [          ]  ETA: --:--"))
    ;; BB-TEST-PATCH: Make windows compatible
    (is (= (str/trim (with-out-str (pr/print (pr/done bar) {:length 10})))
           "0/50     0% [          ]  ETA: --:--"))))
