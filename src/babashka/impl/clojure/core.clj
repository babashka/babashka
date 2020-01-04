(ns babashka.impl.clojure.core
  {:no-doc true}
  (:refer-clojure :exclude [future])
  (:require [borkdude.graal.locking :as locking]))

(defn locking* [form bindings v f & args]
  (apply @#'locking/locking form bindings v f args))

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
   'Throwable->map Throwable->map
   'compare-and-set! compare-and-set!})
