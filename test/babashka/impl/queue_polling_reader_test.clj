(ns babashka.impl.queue-polling-reader-test
  (:require [clojure.test :refer [deftest is testing]])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.util.concurrent LinkedBlockingQueue]
           [nrepl.in QueuePollingReader]))

(set! *warn-on-reflection* true)

(defn- slow-chunk
  "A stdin chunk whose enqueue takes time: addAll pulls one char per step,
  so a reader draining concurrently can land inside the enqueue."
  [^String s]
  (map (fn [c] (Thread/sleep 5) c) s))

(defn- read-with-slow-stdin
  "Reads one form while a chunk is enqueued slowly, the enqueue held under
  the queue's monitor as nrepl's stdin op holds it. Returns how many times
  the reader asked for input."
  []
  (let [q (LinkedBlockingQueue.)
        asked (atom 0)
        request (fn [] (or (.poll q) (do (swap! asked inc) (.take q))))
        r (LineNumberingPushbackReader. (QueuePollingReader. q request))
        form (future (read r))]
    (Thread/sleep 20)
    (locking q (.addAll q (slow-chunk ":ohai\n")))
    (is (= :ohai (deref form 3000 :timeout)))
    @asked))

(deftest one-chunk-is-one-request-test
  ;; the drain in QueuePollingReader must run under the same monitor the
  ;; enqueue holds, else it sees a partial chunk, comes up one delimiter
  ;; short and asks the client for input again. This test holds the enqueue
  ;; side's lock itself, so it guards the Java drain, not session.clj.
  (testing "a form split across the enqueue is still requested once"
    (is (= 1 (read-with-slow-stdin)))))
