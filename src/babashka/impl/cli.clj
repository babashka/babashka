(ns babashka.impl.cli
  (:require
   [babashka.cli]
   [sci.core :as sci]))

(def cns (sci/create-ns 'babashka.cli))

(def cli-namespace
  (sci/copy-ns babashka.cli cns))

(defn exec-fn-snippet [sym]
  (format "
(do
(require '[babashka.cli])
(let [var (requiring-resolve `%1$s)
      var-meta (meta var)
      ns (:ns (meta var))
      ns-meta (meta ns)
      ct (babashka.tasks/current-task)
      cli-opts (babashka.cli/merge-opts (:org.babashka/cli ns-meta)
                                        (:org.babashka/cli var-meta)
                                        (:org.babashka/cli ct))
      opts (babashka.cli/parse-opts *command-line-args* cli-opts)
      task-exec-args (:exec-args ct)
      cli-exec-args (:exec-args cli-opts)
      opts (babashka.cli/merge-opts cli-exec-args task-exec-args opts)]
(var opts)))"
          sym))
