(ns babashka.deps
  (:require [babashka.impl.common :as common]
            [babashka.impl.process :as pp]
            [babashka.impl.tools-deps :as tools-deps]
            [babashka.process :as p]
            [borkdude.deps :as deps]
            [sci.core :as sci]))

(defn ^:no-doc make-classpath-fn
  "Returns an in-process classpath resolver when getenv returns native for
  BABASHKA_DEPS_RESOLVER, or nil to use the JVM resolver.
  dir is the project directory. getenv maps environment names to values."
  [dir getenv]
  (when (= "native" (getenv "BABASHKA_DEPS_RESOLVER"))
    (fn [{:keys [args out]}]
      (if (= :string out)
        {:out (with-out-str (tools-deps/make-classpath! dir args))}
        (do (tools-deps/make-classpath! dir args)
            {:out nil})))))

(defn ^:no-doc getenv-fn
  "Returns an environment lookup function for deps.clj.
  env replaces the process environment. extra-env supplies overrides."
  [env extra-env]
  (fn [k]
    (if env
      (get (merge env extra-env) k)
      (or (get extra-env k) (System/getenv k)))))

(def ^:private gitlibs-dir-set (atom nil))

(defn ^:no-doc gitlibs-dir!
  "Points tools.gitlibs at the GITLIBS in getenv through the
  clojure.gitlibs.dir property, and clears it again when a later resolve
  has none. gitlibs reads its configuration once into a delay, so a change
  also replaces that delay in the interpreter."
  [getenv]
  (let [dir (getenv "GITLIBS")]
    (when (not= dir @gitlibs-dir-set)
      (if dir
        (System/setProperty "clojure.gitlibs.dir" dir)
        (System/clearProperty "clojure.gitlibs.dir"))
      (reset! gitlibs-dir-set dir)
      (sci/eval-string*
       (common/ctx)
       "(when (find-ns 'clojure.tools.gitlibs.config)
          (alter-var-root (ns-resolve 'clojure.tools.gitlibs.config 'CONFIG)
                          (constantly (delay ((deref (ns-resolve 'clojure.tools.gitlibs.config 'init-config)))))))"))))

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
        getenv (getenv-fn (:env opts) (:extra-env opts))
        _ (gitlibs-dir! getenv)]
    (binding [*in* @sci/in
              *out* @sci/out
              *err* @sci/err
              deps/*dir* (:dir opts)
              deps/*getenv-fn* getenv
              deps/*make-classpath-fn* (or (make-classpath-fn (:dir opts) getenv)
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
