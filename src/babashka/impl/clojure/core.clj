(ns babashka.impl.clojure.core
  {:no-doc true}
  (:refer-clojure :exclude [future read read-string])
  (:require [borkdude.graal.locking :as locking]
            [sci.core :as sci]
            [sci.impl.namespaces :refer [copy-core-var]]))

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
  {'file-seq (copy-core-var file-seq)
   'agent (copy-core-var agent)
   'send (copy-core-var send)
   'send-off (copy-core-var send-off)
   'promise (copy-core-var promise)
   'deliver (copy-core-var deliver)
   'locking (with-meta locking* {:sci/macro true})
   'shutdown-agents (copy-core-var shutdown-agents)
   'slurp (copy-core-var slurp)
   'spit (copy-core-var spit)
   'time (with-meta time* {:sci/macro true})
   'Throwable->map (copy-core-var Throwable->map)
   'compare-and-set! (copy-core-var compare-and-set!)
   '*data-readers* (sci/new-dynamic-var '*data-readers* nil)
   'xml-seq (copy-core-var xml-seq)})
