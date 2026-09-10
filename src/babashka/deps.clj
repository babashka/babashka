(ns babashka.deps
  (:require [babashka.impl.common :as common]
            [babashka.impl.process :as pp]
            [babashka.impl.tools-deps :as tools-deps]
            [babashka.process :as p]
            [borkdude.deps :as deps]
            [sci.core :as sci]))

(defn ^:no-doc make-classpath-fn
  "Returns an in-process classpath resolver for :bb, the default, or nil
  for :jvm. resolver takes precedence over BABASHKA_DEPS_RESOLVER from
  getenv. dir is the project directory. getenv maps environment names to
  values."
  [dir getenv resolver]
  (let [r (some-> (or resolver (not-empty (getenv "BABASHKA_DEPS_RESOLVER"))) name)]
    (when-not (contains? #{nil "bb" "jvm"} r)
      (throw (ex-info (str "Unknown deps resolver " r ", use bb or jvm") {:resolver r})))
    (when-not (= "jvm" r)
      (fn [{:keys [args out]}]
        ;; deps.clj has bound *getenv-fn* by now, so this is the config dir
        ;; of the call's own environment, -Srepro or not
        (let [opts {:config-dir (deps/get-config-dir)
                    :getenv deps/*getenv-fn*}]
          (if (= :string out)
            {:out (with-out-str (tools-deps/make-classpath! dir args opts))}
            (do (tools-deps/make-classpath! dir args opts)
                {:out nil})))))))

(defn ^:no-doc getenv-fn
  "Returns an environment lookup function for deps.clj.
  env replaces the process environment. extra-env supplies overrides."
  [env extra-env]
  (fn [k]
    (if env
      (get (merge env extra-env) k)
      (or (get extra-env k) (System/getenv k)))))

(defn clojure
  "Starts clojure similar to CLI. Use `rlwrap bb` for `clj`-like invocation.
  Invokes java with babashka.process/process for `-M`, `-X` and `-A`
  and returns the associated record. Default options passed to
  babashka.process/process are:

  {:in  :inherit
   :out :inherit
   :err :inherit
   :shutdown p/destroy-tree}

  which can be overriden with opts.

  Returns `nil` and prints to *out* for --help, -Spath, -Sdescribe and
  -Stree.

  Examples:

  (-> (clojure {:out :string} '-M '-e '(+ 1 2 3)]) deref :out) returns
  \"6\n\".

  (-> @(clojure) :exit) starts a clojure REPL, waits for it
  to finish and returns the exit code from the process."
  [& args]
  (let [{:keys [cmd opts prev]} (p/parse-args args)
        opts (merge {:in  :inherit
                     :out :inherit
                     :err :inherit
                     :shutdown p/destroy-tree}
                    opts)
        getenv (getenv-fn (:env opts) (:extra-env opts))]
    (binding [*in* @sci/in
              *out* @sci/out
              *err* @sci/err
              deps/*dir* (:dir opts)
              deps/*getenv-fn* getenv
              deps/*make-classpath-fn* (or (make-classpath-fn (:dir opts) getenv
                                                              (:deps-resolver @common/bb-edn))
                                           deps/*make-classpath-fn*)
              deps/*aux-process-fn* (fn [{:keys [cmd out]}]
                                      (pp/shell (assoc opts :out out :cmd cmd)))
              deps/*clojure-process-fn* (fn [{:keys [cmd]}]
                                          (pp/process* {:cmd cmd
                                                        :prev prev
                                                        :opts opts}))
              deps/*exit-fn* (fn [{:keys [message]}]
                               (when message
                                 (throw (Exception. message))))]
      (apply deps/-main cmd))))
