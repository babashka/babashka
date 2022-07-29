(ns babashka.impl.cli
  (:require
   [babashka.cli]
   [sci.core :as sci]))

(def cns (sci/create-ns 'babashka.cli))

(def cli-namespace
  (sci/copy-ns babashka.cli cns))

(defn exec-fn-snippet [ns var-name]
  (format "
(require '%1$s)
(require '[babashka.cli])
(def ns-meta (meta (find-ns '%1$s)))
(def var-meta (meta (resolve '%1$s/%2$s)))
(def cli-opts (babashka.cli/merge-opts (:org.babashka/cli ns-meta) (:org.babashka/cli var-meta)))
(def opts (babashka.cli/parse-opts *command-line-args* cli-opts))
(def task-exec-args (:exec-args (babashka.tasks/current-task)))
(def cli-exec-args (:exec-args cli-opts))
(def opts (babashka.cli/merge-opts cli-exec-args task-exec-args opts))
(%1$s/%2$s opts)"
          ns var-name))
