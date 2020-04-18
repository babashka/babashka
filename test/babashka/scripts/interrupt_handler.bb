(require '[babashka.signal :as signal])

(signal/add-interrupt-handler! :quit (fn [k] (println "bye1" k)))
(signal/add-interrupt-handler! :quit2 (fn [k] (println "bye2" k)))

(System/exit 0)
