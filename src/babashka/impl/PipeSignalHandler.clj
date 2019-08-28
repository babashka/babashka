(ns babashka.impl.PipeSignalHandler
  {:no-doc true}
  (:import [sun.misc Signal]
           [sun.misc SignalHandler])
  (:gen-class
   :name babashka.impl.PipeSignalHandler
   :implements [sun.misc.SignalHandler]))

(def pipe-state (volatile! nil))

(defn -handle [_this _signal]
  (vreset! pipe-state :PIPE))

(defn pipe-signal-received? []
  (identical? :PIPE @pipe-state))

(defn handle-pipe! []
  (Signal/handle
   (Signal. "PIPE")
   (babashka.impl.PipeSignalHandler.)))
