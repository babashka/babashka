(ns babashka.impl.Thread)

(defn sleep
  ([millis] (Thread/sleep millis))
  ([millis nanos] (Thread/sleep millis nanos)))

(def thread-bindings
  {'Thread/sleep sleep})
