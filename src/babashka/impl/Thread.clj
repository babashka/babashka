(ns babashka.impl.Thread
  {:no-doc true})

(defn sleep
  ([millis] (Thread/sleep millis))
  ([millis nanos] (Thread/sleep millis nanos)))

(def thread-bindings
  {'Thread/sleep sleep})
