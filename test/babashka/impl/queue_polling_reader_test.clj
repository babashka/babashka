(ns babashka.impl.queue-polling-reader-test
  (:require [clojure.test :refer [deftest is testing]])
  (:import [clojure.lang LineNumberingPushbackReader]
           [nrepl.in QueuePollingReader]))

(set! *warn-on-reflection* true)

(deftest one-chunk-is-one-request-test
  (testing "a form in one stdin chunk requests input once"
    (let [asked (atom 0)
          qpr (QueuePollingReader. (fn [] (swap! asked inc)))
          form (future (read (LineNumberingPushbackReader. qpr)))]
      (while (zero? @asked) (Thread/sleep 1))
      (.addInput qpr ":ohai\n")
      (is (= :ohai (deref form 3000 :timeout)))
      (is (= 1 @asked)))))
