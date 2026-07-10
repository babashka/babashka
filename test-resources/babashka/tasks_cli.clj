(ns babashka.tasks-cli)

(defn outdated [{:keys [opts]}]
  (prn (assoc opts :ran :outdated)))

(defn clean [{:keys [opts]}]
  (prn (assoc opts :ran :clean)))

;; Root :cli fn: spec from this meta, called with dispatch's result map.
(defn run-dev
  "Runs the dev system"
  {:org.babashka/cli {:spec {:port {:coerce :int :desc "port"}}}}
  [{:keys [opts]}]
  (prn (assoc opts :ran :run-dev)))

;; Subcommand fn carrying its own spec: spec/args->opts from this meta rather
;; than the :cmd node; called with dispatch's result map.
(defn lock
  "Lock deployment"
  {:org.babashka/cli {:spec {:environment {:require true}
                             :message {:alias :m :desc "message"}}
                      :args->opts [:environment]}}
  [{:keys [opts]}]
  (prn (assoc opts :ran :lock)))

;; :exec-fn node: receives just the parsed opts (not the dispatch map).
(defn deploy-x
  "Deploy it"
  {:org.babashka/cli {:spec {:env {:require true}}
                      :args->opts [:env]}}
  [opts]
  (prn (assoc opts :ran :exec-only)))
