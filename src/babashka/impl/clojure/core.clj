(ns babashka.impl.clojure.core
  {:no-doc true}
  (:refer-clojure :exclude [future read read-string])
  (:require [borkdude.graal.locking :as locking]
            [clojure.tools.reader.reader-types :as r]
            [sci.impl.namespaces :refer [copy-core-var]]
            [sci.impl.parser :as parser]))

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

(defn read-string
  ([sci-ctx s]
   (let [reader (r/indexing-push-back-reader (r/push-back-reader s))]
     (parser/parse-next sci-ctx reader)))
  #_([opts s] (clojure.lang.RT/readString s opts)))

(def core-extras
  {'file-seq (copy-core-var file-seq)
   'agent agent
   'instance? instance? ;; TODO: move to sci
   'send send
   'send-off send-off
   'promise promise
   'deliver deliver
   'locking (with-meta locking* {:sci/macro true})
   'shutdown-agents shutdown-agents
   'slurp (copy-core-var slurp)
   'read-string (with-meta read-string {:sci.impl/op :needs-ctx})
   'spit (copy-core-var spit)
   'time (with-meta time* {:sci/macro true})
   'Throwable->map Throwable->map
   'compare-and-set! compare-and-set!})
