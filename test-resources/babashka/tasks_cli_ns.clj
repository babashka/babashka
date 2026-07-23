(ns babashka.tasks-cli-ns
  "Namespace-level :org.babashka/cli defaults, merged under fn metadata like bb -x."
  {:org.babashka/cli {:restrict true
                      :spec {:verbose {:coerce :boolean :desc "Verbose"}}}})

(defn go
  "Runs it"
  {:org.babashka/cli {:spec {:port {:coerce :int :desc "Port"}}}}
  [{:keys [opts]}]
  (prn (assoc opts :ran :go)))
