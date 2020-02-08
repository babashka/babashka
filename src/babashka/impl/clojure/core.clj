(ns babashka.impl.clojure.core
  {:no-doc true}
  (:refer-clojure :exclude [future])
  (:require [borkdude.graal.locking :as locking]))

(defn locking* [form bindings v f & args]
  (apply @#'locking/locking form bindings v f args))

(defn time*
  "Evaluates expr and prints the time it took.  Returns the value of
  expr."
  [_ _ expr]
  `(let [start# (. System (nanoTime))
         ret# ~expr]
     (prn (str "Elapsed time: " (/ (double (- (. System (nanoTime)) start#)) 1000000.0) " msecs"))
     ret#))

(def core-extras
  {'file-seq file-seq
   'agent agent
   'instance? instance? ;; TODO: move to sci
   'send send
   'send-off send-off
   'promise promise
   'deliver deliver
   'locking (with-meta locking* {:sci/macro true})
   'shutdown-agents shutdown-agents
   'slurp slurp
   'spit spit
   'time (with-meta time* {:sci/macro true})
   'Throwable->map Throwable->map
   'compare-and-set! compare-and-set!})
