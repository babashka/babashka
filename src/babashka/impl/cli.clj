(ns babashka.impl.cli
  (:require
   [babashka.cli]
   [sci.core :as sci]))

(def cns (sci/create-ns 'babashka.cli))

(def cli-namespace
  (sci/copy-ns babashka.cli cns))

(defn exec-fn-snippet
  ([sym] (exec-fn-snippet sym nil))
  ([sym extra-opts]
   (format "
(do
(require '[babashka.cli])
(let [extra-opts '%2$s
      the-var (requiring-resolve `%1$s)
      the-var-meta (meta the-var)
      ns (:ns (meta the-var))
      ns-meta (meta ns)
      ct (babashka.tasks/current-task)
      cli-opts (babashka.cli/merge-opts (:org.babashka/cli ns-meta)
                                        (:org.babashka/cli the-var-meta)
                                        (:org.babashka/cli ct)
                                        extra-opts)
      opts (babashka.cli/parse-opts *command-line-args* cli-opts)
      task-exec-args (:exec-args ct)
      cli-exec-args (:exec-args cli-opts)
      opts (babashka.cli/merge-opts cli-exec-args task-exec-args opts)]
(the-var opts)))"
           sym
           (pr-str extra-opts))))
