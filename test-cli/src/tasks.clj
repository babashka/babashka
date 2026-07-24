(ns tasks
  ;; Parsing defaults for every function in this namespace, like `bb -x`.
  {:org.babashka/cli {:restrict true :restrict-args true}}
  (:require [babashka.cli :as cli]))

(def environments ["dev" "staging" "prod"])

(defn red-error
  "Prints the standard error message in red and exits, replacing the default
  error output."
  [data]
  (binding [*out* *err*]
    (println (str "\u001b[31m" (cli/format-command-error data) "\u001b[0m")))
  (System/exit 1))

;; Dispatch defaults for every CLI task, referenced from bb.edn as
;; :tasks {:cli tasks/base-opts}. A def may hold functions; bb.edn cannot.
(def base-opts {:error-fn red-error})

(defn clean
  "Removes build artifacts"
  {:org.babashka/cli {:spec {:dry-run {:coerce :boolean :desc "Only print what would be removed"}}}}
  [{:keys [dry-run]}]
  (println (if dry-run "Would remove target/" "Removing target/")))

(defn dev
  "Starts the dev system"
  {:org.babashka/cli {:spec {:port {:coerce :int :default 8080 :desc "HTTP port"}
                             :sandbox {:coerce :boolean :alias :s :desc "Run sandboxed"}}}}
  [{:keys [port sandbox]}]
  (println "Starting dev system on port" port (if sandbox "(sandboxed)" "(unrestricted)")))

(defn lock
  "Locks deployments"
  {:org.babashka/cli {:spec {:environment {:desc "Target environment"
                                           :enum environments
                                           :require true
                                           :positional true}
                             :message {:alias :m :desc "Lock message" :require true}}
                      :args->opts [:environment]}}
  [{:keys [environment message]}]
  (println "Locking" environment "-" message))

(defn unlock
  "Unlocks deployments"
  {:org.babashka/cli {:spec {:environment {:desc "Target environment"
                                           :enum environments
                                           :require true
                                           :positional true}}
                      :args->opts [:environment]}}
  [{:keys [environment]}]
  (println "Unlocking" environment))
