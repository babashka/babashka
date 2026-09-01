(ns babashka.deps
  (:require [babashka.impl.features :as features]
            [babashka.impl.process :as pp]
            [babashka.process :as p]
            [borkdude.deps :as deps]
            [sci.core :as sci]))

;; With tools.deps in the image, make-classpath2 runs in this process instead
;; of in a java subprocess. Resolved at build time.
(def ^:private make-classpath!
  (when features/tools-deps? @(resolve 'babashka.impl.tools-deps/make-classpath!)))

(def ^:private make-classpath-ns "clojure.tools.deps.script.make-classpath2")

(defn- make-classpath-args [cmd]
  (when (some #{make-classpath-ns} cmd)
    (vec (rest (drop-while #(not= make-classpath-ns %) cmd)))))

(defn ^:no-doc aux-process-fn
  "Returns an aux process fn for deps.clj. It runs make-classpath2 in this
  process when tools.deps is in the image and calls spawn otherwise. dir is
  the project directory."
  [dir spawn]
  (fn [{:keys [cmd out] :as m}]
    (if-let [args (and make-classpath! (make-classpath-args cmd))]
      (if (= :string out)
        {:exit 0 :out (with-out-str (make-classpath! dir args))}
        (do (make-classpath! dir args)
            {:exit 0}))
      (do (deps/check-java-cmd! cmd)
          (spawn m)))))

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
                    opts)]
    (binding [*in* @sci/in
              *out* @sci/out
              *err* @sci/err
              deps/*dir* (:dir opts)
              deps/*aux-process-fn* (aux-process-fn (:dir opts)
                                                    (fn [{:keys [cmd out]}]
                                                      (pp/shell (assoc opts :out out :cmd cmd))))
              deps/*clojure-process-fn* (fn [{:keys [cmd]}]
                                          (pp/process* {:cmd cmd
                                                        :prev prev
                                                        :opts opts}))
              deps/*exit-fn* (fn [{:keys [message]}]
                               (when message
                                 (throw (Exception. message))))]
      (apply deps/-main cmd))))
