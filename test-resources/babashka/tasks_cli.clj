(ns babashka.tasks-cli)

(defn outdated [{:keys [opts]}]
  (prn (assoc opts :ran :outdated)))

(defn clean [{:keys [opts]}]
  (prn (assoc opts :ran :clean)))

;; :exec-fn root fn: receives the parsed opts directly, spec from this meta.
(defn run-dev
  "Runs the dev system"
  {:org.babashka/cli {:spec {:port {:coerce :int :desc "port"}}}}
  [opts]
  (prn (assoc opts :ran :exec-fn)))
