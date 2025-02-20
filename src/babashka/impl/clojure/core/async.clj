(ns babashka.impl.clojure.core.async
  {:no-doc true}
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as protocols]
            [clojure.core.async.impl.dispatch :as dispatch]
            [sci.core :as sci :refer [copy-var]]
            [sci.impl.copy-vars :refer [macrofy]]
            [sci.impl.vars :as vars])
  (:import [java.util.concurrent Executors ExecutorService ThreadFactory]))

(set! *warn-on-reflection* true)

#_(def ^java.util.concurrent.Executor executor @#'async/thread-macro-executor)
(def executor-for
  "Given a workload tag, returns an ExecutorService instance and memoizes the result. By
  default, core.async will defer to a user factory (if provided via sys prop) or construct
  a specialized ExecutorService instance for each tag :io, :compute, and :mixed. When
  given the tag :core-async-dispatch it will default to the executor service for :io."
  (memoize
   (fn ^ExecutorService [workload]
     (let [sysprop-factory nil #_(when-let [esf (System/getProperty "clojure.core.async.executor-factory")]
                             (requiring-resolve (symbol esf)))
           sp-exec (and sysprop-factory (sysprop-factory workload))]
       (or sp-exec
           (if (= workload :core-async-dispatch)
             (executor-for :io)
             (@#'dispatch/create-default-executor workload)))))))

(alter-var-root #'dispatch/executor-for (constantly executor-for))

(defn exec
  [^Runnable r workload]
  (let [^ExecutorService e (executor-for workload)]
    (.execute e r)))

(alter-var-root #'dispatch/exec (constantly exec))

(def ^java.util.concurrent.Executor virtual-executor
  (try (eval '(java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor))
       (catch Exception _ nil)))

(defn thread-call
  "Executes f in another thread, returning immediately to the calling
  thread. Returns a channel which will receive the result of calling
  f when completed, then close. workload is a keyword that describes
  the work performed by f, where:

  :io - may do blocking I/O but must not do extended computation
  :compute - must not ever block
  :mixed - anything else (default)

  when workload not supplied, defaults to :mixed"
  ([f] (thread-call f :mixed))
  ([f workload]
   (let [c (async/chan 1)
         returning-to-chan (fn [bf]
                             #(try
                                (when-some [ret (bf)]
                                  (async/>!! c ret))
                                (finally (async/close! c))))]
     (-> f bound-fn* returning-to-chan (exec workload))
     c)))

(defn -vthread-call
  "Executes f in another virtual thread, returning immediately to the calling
  thread. Returns a channel which will receive the result of calling
  f when completed, then close."
  [f]
  (let [c (async/chan 1)]
    (let [binds (vars/get-thread-binding-frame)]
      (.execute virtual-executor
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

(defn -vthread
  [_ _ & body]
  `(~'clojure.core.async/-vthread-call (fn [] ~@body)))

(defn alt!!
  "Like alt!, except as if by alts!!, will block until completed, and
  not intended for use in (go ...) blocks."
  [_ _ & clauses]
  (async/do-alt 'clojure.core.async/alts!! clauses))

(defn go-loop
  [_ _ bindings & body]
  (list 'clojure.core.async/go (list* 'loop bindings body)))

(def core-async-namespace (sci/create-ns 'clojure.core.async nil))

(defn timeout [ms]
  (if virtual-executor
    (let [chan (async/chan nil)]
      (.execute virtual-executor (fn []
                                   (Thread/sleep (long ms))
                                   (async/close! chan)))
      chan)
    (async/timeout ms)))

(def async-namespace
  {:obj core-async-namespace
   '<!! (copy-var async/<!! core-async-namespace)
   '>!! (copy-var async/>!! core-async-namespace)
   'admix (copy-var async/admix core-async-namespace)
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
   'onto-chan! (copy-var async/onto-chan! core-async-namespace)
   'onto-chan!! (copy-var async/onto-chan!! core-async-namespace)
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
   '-vthread-call (copy-var -vthread-call core-async-namespace)
   'timeout (copy-var timeout core-async-namespace)
   'to-chan (copy-var async/to-chan core-async-namespace)
   'to-chan! (copy-var async/to-chan! core-async-namespace)
   'to-chan!! (copy-var async/to-chan!! core-async-namespace)
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
   'go (if virtual-executor
         (macrofy 'go -vthread core-async-namespace)
         (macrofy 'go thread core-async-namespace))
   '<! (copy-var async/<!! core-async-namespace {:name '<!})
   '>! (copy-var async/>!! core-async-namespace {:name '>!})
   'alt! (macrofy 'alt! alt!! core-async-namespace)
   'alts! (copy-var async/alts!! core-async-namespace {:name 'alts!})
   'go-loop (macrofy 'go-loop go-loop core-async-namespace)})

(def async-protocols-ns (sci/create-ns 'clojure.core.async.impl.protocols nil))

(def async-protocols-namespace
  {:obj async-protocols-ns
   'ReadPort (copy-var protocols/ReadPort async-protocols-ns)})
;; trigger CI
