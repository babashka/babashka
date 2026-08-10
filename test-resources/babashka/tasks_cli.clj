(ns babashka.tasks-cli)

(defn outdated [{:keys [opts]}]
  (prn (assoc opts :ran :outdated)))

(defn clean [{:keys [opts]}]
  (prn (assoc opts :ran :clean)))

;; Root :cli fn: spec from this meta, called with dispatch's result map.
(defn run-dev
  "Runs the dev system"
  {:org.babashka/cli {:spec {:port {:coerce :int :desc "port"}}}}
  [opts]
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

;; A CLI task named in another task's :depends: its spec merges into the
;; target's parse and its handler runs with the keys it declared.
(defn dep-build
  {:org.babashka/cli {:spec {:target {:desc "build target"}}}}
  [opts]
  (prn (assoc opts :ran :dep-build)))

;; Target restricting to its own spec: the dep's options must still parse.
(defn dep-test
  {:org.babashka/cli {:restrict true :spec {:watch {:coerce :boolean}}}}
  [opts]
  (prn (assoc opts :ran :dep-test)))

;; Runner-wide defaults var, referenced from bb.edn as :tasks {:cli
;; babashka.tasks-cli/base-opts}. The error handler throws instead of exiting
;; so in-process tests survive it.
(defn defaults-error [{:keys [cause msg]}]
  (binding [*out* *err*]
    (println (str "DEFAULTS-ERR " cause " | " msg)))
  (throw (ex-info "" {:babashka/exit 1})))

(def base-opts {:restrict-args true :error-fn defaults-error
                :spec {:verbose {:coerce :boolean :desc "Verbose output"}}})

;; Own :error-fn: wins over the base-opts default for this fn's errors.
(defn strict
  "Strict"
  {:org.babashka/cli {:error-fn (fn [{:keys [cause]}]
                                  (binding [*out* *err*]
                                    (println (str "LEAF-ERR " cause)))
                                  (throw (ex-info "" {:babashka/exit 1})))
                      :spec {:env {:require true}}}}
  [opts]
  (prn (assoc opts :ran :strict)))
