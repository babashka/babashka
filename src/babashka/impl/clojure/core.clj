(ns babashka.impl.clojure.core
  {:no-doc true}
  (:refer-clojure :exclude [future]))

(defn future
  [& body]
  `(~'future-call (fn [] ~@body)))

(def core-bindings
  {;; atoms
   'atom atom
   'swap! swap!
   'swap-vals! swap-vals!
   'reset! reset!
   'add-watch add-watch
   'future-call future-call
   'future (with-meta future {:sci/macro true})
   'future-cancel future-cancel
   'future-cancelled? future-cancelled?
   'future-done? future-done?
   'future? future?
   'deref deref
   'agent agent
   'send send
   'send-off send-off
   'promise promise
   'deliver deliver
   'shutdown-agents shutdown-agents
   'run! run!
   'slurp slurp
   'spit spit
   'pmap pmap
   'pr pr
   'pr-str pr-str
   'prn prn
   'prn-str prn-str
   'print-str print-str
   'print print
   'println println
   'println-str println-str
   'flush flush
   'ex-info ex-info
   'ex-data ex-data
   'read-line read-line})
