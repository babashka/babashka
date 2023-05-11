(ns babashka.deps
  (:require [babashka.impl.process :as pp]
            [babashka.process :as p]
            [borkdude.deps :as deps]
            [sci.core :as sci]))

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

  (-> (clojure '[-M -e (+ 1 2 3)] {:out :string}) deref :out) returns
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
              deps/*aux-process-fn* (fn [{:keys [cmd out]}]
                                      (apply pp/shell (assoc opts :out out) cmd))
              deps/*clojure-process-fn* (fn [{:keys [cmd]}]
                                  (pp/process* {:cmd cmd
                                                :prev prev
                                                :opts opts}))
              deps/*exit-fn* (fn [{:keys [message]}]
                               (when message
                                 (throw (Exception. message))))]
      (apply deps/-main cmd))))
