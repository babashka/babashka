(ns babashka.tasks-cli
  (:require [babashka.tasks :as tasks]))

;; Records which task it ran under, to show a dep's handler runs inside that
;; dep's own `*task*` binding and between its `:enter` and `:leave`.
(defn mark-task
  {:org.babashka/cli {:spec {:out {}}}}
  [{:keys [out]}]
  (spit out (str "handler:" (:name (tasks/current-task)) "\n") :append true))

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

;; Under --parallel a dep handler runs on its own thread, where the in-process
;; test harness does not capture *out*. These report through a file instead.
(defn dep-spit
  {:org.babashka/cli {:spec {:out {}}}}
  [{:keys [out]}]
  (spit out "dep\n" :append true))

(defn target-spit
  {:org.babashka/cli {:spec {:out {}}}}
  [{:keys [out]}]
  (spit out "target\n" :append true))

;; Order markers. `mark-a` is slow on purpose: a dep that ignores the graph and
;; launches siblings at once lets the faster `mark-c` write first.
(defn mark-a
  {:org.babashka/cli {:spec {:out {}}}}
  [{:keys [out]}]
  (Thread/sleep 300)
  (spit out "a\n" :append true))

(defn mark-c
  {:org.babashka/cli {:spec {:out {}}}}
  [{:keys [out]}]
  (spit out "c\n" :append true))

;; A rendezvous: write my own marker, then wait for the sibling's. Both markers
;; only ever appear while both handlers are running, so this reports
;; "concurrent" under real parallelism and "serial" when they take turns. Each
;; dep needs its own fn: siblings are handed the same parsed options.
(defn- rendezvous [dir me other]
  (spit (str dir "/" me) "")
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (cond
        (.exists (java.io.File. (str dir "/" other)))
        (spit (str dir "/" me "-result") "concurrent")

        (< deadline (System/currentTimeMillis))
        (spit (str dir "/" me "-result") "serial")

        :else (do (Thread/sleep 20) (recur))))))

(defn rendezvous-a
  {:org.babashka/cli {:spec {:dir {}}}}
  [{:keys [dir]}]
  (rendezvous dir "a" "b"))

(defn rendezvous-b
  {:org.babashka/cli {:spec {:dir {}}}}
  [{:keys [dir]}]
  (rendezvous dir "b" "a"))

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
