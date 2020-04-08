(ns babashka.impl.sigint-handler
  {:no-doc true}
  (:import [sun.misc Signal]
           [sun.misc SignalHandler]))

(set! *warn-on-reflection* true)

(def shutdown-hooks (atom {}))

(defn add-interrupt-handler! [k f]
  (swap! shutdown-hooks assoc k f))

(defn handle-sigint! []
  (let [rt (Runtime/getRuntime)]
    (.addShutdownHook rt
                      (Thread. (fn []
                                 (doseq [[k f] @shutdown-hooks]
                                   (f k)))))
    (Signal/handle
     (Signal. "INT")
     (reify SignalHandler
       (handle [_ _]
         (System/exit 0))))))
