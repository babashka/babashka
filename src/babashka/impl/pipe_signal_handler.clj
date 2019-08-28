(ns babashka.impl.pipe-signal-handler
  {:no-doc true}
  (:import [sun.misc Signal]
           [sun.misc SignalHandler]))

(def pipe-state (volatile! nil))

(defn pipe-signal-received? []
  (identical? :PIPE @pipe-state))

(defn handle-pipe! []
  (Signal/handle
   (Signal. "PIPE")
   (reify SignalHandler
     (handle [_ _]
       (vreset! pipe-state :PIPE)))))
