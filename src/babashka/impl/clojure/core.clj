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
   'reset! reset!
   'add-watch add-watch

   'run! run!
   'slurp slurp
   'spit spit
   'pmap pmap
   'print print
   'flush flush
   'pr-str pr-str
   'prn prn
   'println println
   'future-call future-call
   'future (with-meta future {:sci/macro true})
   'deref deref})
