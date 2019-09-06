(ns babashka.impl.async
  {:no-doc true}
  (:require [clojure.core.async :as async]))

(defn thread
  [& body]
  `(~'async/thread-call (fn [] ~@body)))

(def async-bindings
  {'async/<!! async/<!!
   'async/>!! async/>!!
   'async/admix async/admix
   'async/alts! async/alts!
   'async/alts!! async/alts!!
   'async/buffer async/buffer
   'async/chan async/chan
   'async/close! async/close!
   'async/do-alt async/do-alt
   'async/do-alts async/do-alts
   'async/dropping-buffer async/dropping-buffer
   'async/filter< async/filter<
   'async/filter> async/filter>
   'async/into async/into
   'async/map async/map
   'async/map< async/map<
   'async/map> async/map>
   'async/mapcat< async/mapcat<
   'async/mapcat> async/mapcat>
   'async/merge async/merge
   'async/mix async/mix
   'async/mult async/mult
   'async/offer! async/offer!
   'async/onto-chan async/onto-chan
   ;; TODO: add more from https://clojure.github.io/core.async/
   'async/poll! async/poll!
   'async/pipe async/pipe
   'async/pipeline async/pipeline
   'async/pipeline-blocking async/pipeline-blocking
   'async/pipieline-async async/pipeline-async
   'async/split async/split
   'async/reduce async/reduce
   'async/transduce async/transduce
   'async/thread-call async/thread-call
   'async/thread (with-meta thread {:sci/macro true})
   'async/timeout async/timeout
   'async/to-chan async/to-chan
   'async/take! async/take!
   'async/tap async/tap
   'async/unblocking-buffer? async/unblocking-buffer?
   'async/untap async/untap
   'async/untap-all async/untap-all
   'async/unmix async/unmix
   'async/unmix-all async/unmix-all
   'async/sliding-buffer async/sliding-buffer
   'async/toggle async/toggle
   'async/solo-mode async/solo-mode
   'async/promise-chan async/promise-chan
   'async/pub async/pub
   'async/put! async/put!
   'async/sub async/sub
   'async/unsub async/unsub
   'async/unsub-all async/unsub-all})

