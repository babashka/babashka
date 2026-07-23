(ns tasks)

(def environments #{"dev" "staging" "prod"})

(defn red-error
  "Prints the error in red and exits, replacing the default error output."
  [{:keys [msg]}]
  (binding [*out* *err*]
    (println (str "\u001b[31mError: " msg "\u001b[0m")))
  (System/exit 1))

;; Shared cli defaults for every fn in this ns: fn attr-maps are evaluated, so
;; each fn merges this in. bb.edn cannot hold an :error-fn (it is data).
(def cli-base {:error-fn red-error})

(defn dev
  "Starts the dev system"
  {:org.babashka/cli
   (merge cli-base
          {:spec {:port {:coerce :int :default 8080 :desc "HTTP port"}
                  :sandbox {:coerce :boolean :alias :s :desc "Run sandboxed"}}})}
  [{:keys [port sandbox]}]
  (println "Starting dev system on port" port (if sandbox "(sandboxed)" "(unrestricted)")))

(defn lock
  "Locks deployments"
  {:org.babashka/cli
   (merge cli-base
          {:spec {:environment {:desc "Target environment"
                                :validate environments
                                :require true
                                :positional true}
                  :message {:alias :m :desc "Lock message" :require true}}
           :args->opts [:environment]})}
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
