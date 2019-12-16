(ns babashka.impl.async
  {:no-doc true}
  (:require [clojure.core.async :as async]))

(defn thread
  [_ _ & body]
  `(~'clojure.core.async/thread-call (fn [] ~@body)))

(def async-namespace
  {'<!! async/<!!
   '>!! async/>!!
   'admix async/admix
   'alts! async/alts!
   'alts!! async/alts!!
   'buffer async/buffer
   'chan async/chan
   'close! async/close!
   'do-alt async/do-alt
   'do-alts async/do-alts
   'dropping-buffer async/dropping-buffer
   'filter< async/filter<
   'filter> async/filter>
   'into async/into
   'map async/map
   'map< async/map<
   'map> async/map>
   'mapcat< async/mapcat<
   'mapcat> async/mapcat>
   'merge async/merge
   'mix async/mix
   'mult async/mult
   'offer! async/offer!
   'onto-chan async/onto-chan
   'partition async/partition
   'partition-by async/partition-by
   'pipe async/pipe
   'pipeline async/pipeline
   'pipeline-async async/pipeline-async
   'pipeline-blocking async/pipeline-blocking
   'poll! async/poll!
   'promise-chan async/promise-chan
   'pub async/pub
   'put! async/put!
   'reduce async/reduce
   'remove< async/remove<
   'remove> async/remove>
   'sliding-buffer async/sliding-buffer
   'solo-mode async/solo-mode
   'split async/split
   'sub async/sub
   'take async/take
   'take! async/take!
   'tap async/tap
   'thread (with-meta thread {:sci/macro true})
   'thread-call async/thread-call
   'timeout async/timeout
   'to-chan async/to-chan
   'toggle async/toggle
   'transduce async/transduce
   'unblocking-buffer? async/unblocking-buffer?
   'unique async/unique
   'unmix async/unmix
   'unmix-all async/unmix-all
   'unsub async/unsub
   'unsub-all async/unsub-all
   'untap async/untap
   'untap-all async/untap-all})

