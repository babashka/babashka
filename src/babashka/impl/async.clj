(ns babashka.impl.async
  {:no-doc true}
  (:require [clojure.core.async :as async]))

(defn thread
  [& body]
  `(~'async/thread-call (fn [] ~@body)))

(def async-bindings
  {'async/chan async/chan
   'async/dropping-buffer async/dropping-buffer
   'async/sliding-buffer async/sliding-buffer
   'async/close! async/close!
   'async/>!! async/>!!
   'async/<!! async/<!!
   'async/take! async/take!
   'async/put! async/put!
   'async/thread-call async/thread-call
   'async/thread (with-meta thread {:sci/macro true})})

