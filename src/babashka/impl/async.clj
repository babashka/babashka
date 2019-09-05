(ns babashka.impl.async
  {:no-doc true}
  (:require [clojure.core.async :as async]))

(defn thread
  [& body]
  `(~'async/thread-call (fn [] ~@body)))

(defn alt!!
  [& clauses]
  `(~'async/do-alt ~'async/alts!! ~clauses))

(def async-bindings
  {'async/buffer async/buffer
   'async/dropping-buffer async/dropping-buffer
   'async/sliding-buffer async/sliding-buffer
   'async/unblocking-buffer? async/unblocking-buffer?
   'async/chan async/chan
   'async/promise-chan async/promise-chan
   'async/do-alts async/do-alts
   'async/alts!! async/alts!!
   'async/alts! async/alts!
   'async/do-alt async/do-alt
   ;; doesn't work yet:
   'async/alt!! (with-meta alt!! {:sci/macro true})
   'async/offer! async/offer!
   'async/poll! async/poll!
   'async/thread-call async/thread-call
   'async/thread (with-meta thread {:sci/macro true})
   'async/pipe async/pipe
   'async/pipeline async/pipeline
   'async/pipeline-blocking async/pipeline-blocking
   'async/pipieline-async async/pipeline-async
   'async/split async/split
   'async/reduce async/reduce
   'async/transduce async/transduce
   'async/onto-chan async/onto-chan
   'async/to-chan async/to-chan
   'async/mult async/mult
   'async/tap async/tap
   'async/untap async/untap
   'async/untap-all async/untap-all
   'async/mix async/mix
   'async/admix async/admix
   'async/unmix async/unmix
   'async/unmix-all async/unmix-all
   'async/toggle async/toggle
   'async/solo-mode async/solo-mode
   'async/pub async/pub
   'async/sub async/sub
   'async/unsub async/unsub
   'async/unsub-all async/unsub-all
   ;; TODO add more
   'async/close! async/close!
   'async/>!! async/>!!
   'async/<!! async/<!!
   'async/take! async/take!
   'async/put! async/put!
   'async/timeout async/timeout})

