(ns babashka.impl.clojure.core.async
  {:no-doc true}
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as protocols]
            [sci.impl.namespaces :refer [copy-var macrofy]]
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

(def core-async-namespace (vars/->SciNamespace 'clojure.core.async nil))

(def async-namespace
  {:obj core-async-namespace
   '<!! (copy-var async/<!! core-async-namespace)
   '>!! (copy-var async/>!! core-async-namespace)
   'admix (copy-var async/admix core-async-namespace)
   'alts! (copy-var async/alts! core-async-namespace)
   'alts!! (copy-var async/alts!! core-async-namespace)
   'alt!! (macrofy 'alt!! alt!! core-async-namespace)
   'buffer (copy-var async/buffer core-async-namespace)
   'chan (copy-var async/chan core-async-namespace)
   'close! (copy-var async/close! core-async-namespace)
   'do-alt (copy-var async/do-alt core-async-namespace)
   'do-alts (copy-var async/do-alts core-async-namespace)
   'dropping-buffer (copy-var async/dropping-buffer core-async-namespace)
   'filter< (copy-var async/filter< core-async-namespace)
   'filter> (copy-var async/filter> core-async-namespace)
   'into (copy-var async/into core-async-namespace)
   'map (copy-var async/map core-async-namespace)
   'map< (copy-var async/map< core-async-namespace)
   'map> (copy-var async/map> core-async-namespace)
   'mapcat< (copy-var async/mapcat< core-async-namespace)
   'mapcat> (copy-var async/mapcat> core-async-namespace)
   'merge (copy-var async/merge core-async-namespace)
   'mix (copy-var async/mix core-async-namespace)
   'mult (copy-var async/mult core-async-namespace)
   'offer! (copy-var async/offer! core-async-namespace)
   'onto-chan (copy-var async/onto-chan core-async-namespace)
   'partition (copy-var async/partition core-async-namespace)
   'partition-by (copy-var async/partition-by core-async-namespace)
   'pipe (copy-var async/pipe core-async-namespace)
   'pipeline (copy-var async/pipeline core-async-namespace)
   'pipeline-async (copy-var async/pipeline-async core-async-namespace)
   'pipeline-blocking (copy-var async/pipeline-blocking core-async-namespace)
   'poll! (copy-var async/poll! core-async-namespace)
   'promise-chan (copy-var async/promise-chan core-async-namespace)
   'pub (copy-var async/pub core-async-namespace)
   'put! (copy-var async/put! core-async-namespace)
   'reduce (copy-var async/reduce core-async-namespace)
   'remove< (copy-var async/remove< core-async-namespace)
   'remove> (copy-var async/remove> core-async-namespace)
   'sliding-buffer (copy-var async/sliding-buffer core-async-namespace)
   'solo-mode (copy-var async/solo-mode core-async-namespace)
   'split (copy-var async/split core-async-namespace)
   'sub (copy-var async/sub core-async-namespace)
   'take (copy-var async/take core-async-namespace)
   'take! (copy-var async/take! core-async-namespace)
   'tap (copy-var async/tap core-async-namespace)
   'thread (macrofy 'thread thread core-async-namespace)
   'thread-call (copy-var thread-call core-async-namespace)
   'timeout (copy-var async/timeout core-async-namespace)
   'to-chan (copy-var async/to-chan core-async-namespace)
   'toggle (copy-var async/toggle core-async-namespace)
   'transduce (copy-var async/transduce core-async-namespace)
   'unblocking-buffer? (copy-var async/unblocking-buffer? core-async-namespace)
   'unique (copy-var async/unique core-async-namespace)
   'unmix (copy-var async/unmix core-async-namespace)
   'unmix-all (copy-var async/unmix-all core-async-namespace)
   'unsub (copy-var async/unsub core-async-namespace)
   'unsub-all (copy-var async/unsub-all core-async-namespace)
   'untap (copy-var async/untap core-async-namespace)
   'untap-all (copy-var async/untap-all core-async-namespace)
   ;; polyfill
   'go (macrofy 'go thread core-async-namespace)
   '<! (copy-var async/<!! core-async-namespace)
   '>! (copy-var async/>!! core-async-namespace)
   'alt! (macrofy 'alt! alt!! core-async-namespace)
   'go-loop (macrofy 'go-loop go-loop core-async-namespace)})

(def async-protocols-ns (vars/->SciNamespace 'clojure.core.async.impl.protocols nil))

(def async-protocols-namespace
  {:obj async-protocols-ns
   'ReadPort (copy-var protocols/ReadPort async-protocols-ns)})
