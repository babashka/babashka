(ns babashka.impl.pipe-signal-handler
  {:no-doc true}
  (:import [sun.misc Signal]
           [sun.misc SignalHandler])
  (:require [clojure.string :as str]))

(def pipe-state (volatile! nil))

(defn pipe-signal-received? []
  (identical? :PIPE @pipe-state))

(defn handle-pipe! []
  (when-not (or (= "true" (System/getenv "BABASHKA_DISABLE_PIPE_HANDLER"))
                (str/includes?
                 ;; see https://github.com/oracle/graal/issues/1784
                 (str/lower-case (System/getProperty "os.name"))
                 "win"))
    (Signal/handle
     (Signal. "PIPE")
     (reify SignalHandler
       (handle [_ _]
         (vreset! pipe-state :PIPE))))))
