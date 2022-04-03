(ns babashka.impl.sigint-handler
  {:no-doc true}
  (:import [sun.misc Signal]
           [sun.misc SignalHandler]))

(set! *warn-on-reflection* true)

(defn handle-sigint! []
  (when-not (= "true" (System/getenv "BABASHKA_DISABLE_SIGNAL_HANDLERS"))
    (Signal/handle
     (Signal. "INT")
     (reify SignalHandler
       (handle [_ _]
         ;; This is needed to run shutdown hooks on interrupt, System/exit triggers those
         (System/exit 130))))))
