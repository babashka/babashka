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
  {:org.babashka/cli {:spec {:environment {:require true
                                           :validate #{"production" "staging"}}
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

;; :cmd tree on fn metadata; a var literal needs no quoting and carries the
;; spec and docstring of its fn.
(defn tree-root
  "Tree root"
  {:org.babashka/cli {:cmd {"go" {:exec-fn #'deploy-x}}}}
  [{:keys [opts]}]
  (prn (assoc opts :ran :tree-root)))

;; The unquoted trap: this metadata map is evaluated, so the key tree-root
;; below is the function value, not a name. bb rejects it with an explanation.
(defn trap-root
  "Trap"
  {:org.babashka/cli {:cmd {tree-root {:exec-fn #'deploy-x}}}}
  [{:keys [opts]}]
  (prn opts))
