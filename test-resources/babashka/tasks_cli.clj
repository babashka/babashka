(ns babashka.tasks-cli)

(defn outdated [{:keys [opts]}]
  (prn (assoc opts :ran :outdated)))

(defn clean [{:keys [opts]}]
  (prn (assoc opts :ran :clean)))
