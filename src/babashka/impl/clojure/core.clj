(ns babashka.impl.clojure.core
  (:refer-clojure :exclude [future]))

(defn future
  [& body]
  `(~'future-call (fn [] ~@body)))

(def core-bindings
  {'run! run!
   'slurp slurp
   'spit spit
   'pmap pmap
   'print print
   'pr-str pr-str
   'prn prn
   'println println
   'future-call future-call
   'future (with-meta future {:sci/macro true})
   'deref deref})
