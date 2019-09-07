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
   'async/partition async/partition
   'async/partition-by async/partition-by
   'async/pipe async/pipe
   'async/pipeline async/pipeline
   'async/pipeline-async async/pipeline-async
   'async/pipeline-blocking async/pipeline-blocking
   'async/poll! async/poll!
   'async/promise-chan async/promise-chan
   'async/pub async/pub
   'async/put! async/put!
   'async/reduce async/reduce
   'async/remove< async/remove<
   'async/remove> async/remove>
   'async/sliding-buffer async/sliding-buffer
   'async/solo-mode async/solo-mode
   'async/split async/split
   'async/sub async/sub
   'async/take async/take
   'async/take! async/take!
   'async/tap async/tap
   'async/thread (with-meta thread {:sci/macro true})
   'async/thread-call async/thread-call
   'async/timeout async/timeout
   'async/to-chan async/to-chan
   'async/toggle async/toggle
   'async/transduce async/transduce
   'async/unblocking-buffer? async/unblocking-buffer?
   'async/unique async/unique
   'async/unmix async/unmix
   'async/unmix-all async/unmix-all
   'async/unsub async/unsub
   'async/unsub-all async/unsub-all
   'async/untap async/untap
   'async/untap-all async/untap-all})

