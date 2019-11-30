(ns babashka.impl.clojure.core
  {:no-doc true}
  (:refer-clojure :exclude [future]))

(defn future
  [_ _ & body]
  `(~'future-call (fn [] ~@body)))

(defn __close!__ [^java.io.Closeable x]
  (.close x))

(defn with-open*
  [_ _ bindings & body]
  (cond
    (= (count bindings) 0) `(do ~@body)
    (symbol? (bindings 0)) `(let ~(subvec bindings 0 2)
                              (try
                                (with-open ~(subvec bindings 2) ~@body)
                                (finally
                                  (~'__close!__ ~(bindings 0)))))
    :else (throw (IllegalArgumentException.
                  "with-open only allows Symbols in bindings"))))

(def core-extras
  {'file-seq file-seq
   'future-call future-call
   'future (with-meta future {:sci/macro true})
   'future-cancel future-cancel
   'future-cancelled? future-cancelled?
   'future-done? future-done?
   'future? future?
   'agent agent
   'send send
   'send-off send-off
   'promise promise
   'deliver deliver
   'shutdown-agents shutdown-agents
   'slurp slurp
   'spit spit
   'pmap pmap
   '__close!__ __close!__
   'with-open (with-meta with-open* {:sci/macro true})})
