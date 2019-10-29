(ns babashka.impl.clojure.core
  {:no-doc true}
  (:refer-clojure :exclude [future]))

(defn future
  [_ _ & body]
  `(~'future-call (fn [] ~@body)))

(def core-extras
  {'future-call future-call
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
   'pr pr
   'prn prn
   'print print
   'println println
   'println-str println-str
   'flush flush
   'read-line read-line})
