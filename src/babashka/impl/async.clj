(ns babashka.impl.async
  {:no-doc true}
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as protocols]
            [sci.impl.vars :as vars]))

(def ^java.util.concurrent.Executor executor @#'async/thread-macro-executor)

(defn thread-call
  "Executes f in another thread, returning immediately to the calling
  thread. Returns a channel which will receive the result of calling
  f when completed, then close."
  [f]
  (let [c (async/chan 1)]
    (let [binds (vars/get-thread-binding-frame)]
      (.execute executor
                (fn []
                  (vars/reset-thread-binding-frame binds)
                  (try
                    (let [ret (f)]
                      (when-not (nil? ret)
                        (async/>!! c ret)))
                    (finally
                      (async/close! c))))))
    c))

(defn thread
  [_ _ & body]
  `(~'clojure.core.async/thread-call (fn [] ~@body)))

(defn alt!!
  "Like alt!, except as if by alts!!, will block until completed, and
  not intended for use in (go ...) blocks."
  [_ _ & clauses]
  (async/do-alt 'clojure.core.async/alts!! clauses))

(defn go-loop
  [_ _ bindings & body]
  (list 'clojure.core.async/thread (list* 'loop bindings body)))

(def async-namespace
  {'<!! async/<!!
   '>!! async/>!!
   'admix async/admix
   'alts! async/alts!
   'alts!! async/alts!!
   'alt!! (with-meta alt!! {:sci/macro true})
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
   'thread-call thread-call
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
   'untap-all async/untap-all
   ;; polyfill
   'go (with-meta thread {:sci/macro true})
   '<! async/<!!
   '>! async/>!!
   'alt! (with-meta alt!! {:sci/macro true})
   'go-loop (with-meta go-loop {:sci/macro true})})

(def async-protocols-namespace
  {'ReadPort protocols/ReadPort})
