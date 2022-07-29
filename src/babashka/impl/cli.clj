(ns babashka.impl.cli
  (:require
   [babashka.cli]
   [sci.core :as sci]))

(def cns (sci/create-ns 'babashka.cli))

(def cli-namespace
  (sci/copy-ns babashka.cli cns))

(defn exec-fn-snippet [ns var-name]
  (format "
(do
(require '%1$s)
(require '[babashka.cli])
(let [
ns-meta (meta (find-ns '%1$s))
var-meta (meta (resolve '%1$s/%2$s))
cli-opts (babashka.cli/merge-opts (:org.babashka/cli ns-meta) (:org.babashka/cli var-meta))
opts (babashka.cli/parse-opts *command-line-args* cli-opts)
task-exec-args (:exec-args (babashka.tasks/current-task))
cli-exec-args (:exec-args cli-opts)
opts (babashka.cli/merge-opts cli-exec-args task-exec-args opts)]
(%1$s/%2$s opts)))"
          ns var-name))
