(ns tasks)

(def environments #{"dev" "staging" "prod"})

(defn dev
  "Starts the dev system"
  {:org.babashka/cli
   {:spec {:port {:coerce :int :default 8080 :desc "HTTP port"}
           :sandbox {:coerce :boolean :alias :s :desc "Run sandboxed"}}}}
  [{:keys [port sandbox]}]
  (println "Starting dev system on port" port (if sandbox "(sandboxed)" "(unrestricted)")))

(defn lock
  "Locks deployments"
  {:org.babashka/cli
   {:spec {:environment {:desc "Target environment"
                         :validate environments
                         :require true
                         :positional true}
           :message {:alias :m :desc "Lock message" :require true}}
    :args->opts [:environment]}}
  [{:keys [environment message]}]
  (println "Locking" environment "-" message))

(defn unlock
  "Unlocks deployments"
  {:org.babashka/cli
   {:spec {:environment {:desc "Target environment"
                         :validate environments
                         :require true
                         :positional true}}
    :args->opts [:environment]}}
  [{:keys [environment]}]
  (println "Unlocking" environment))
