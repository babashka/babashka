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
(ns exec-%s
  (:require [babashka.cli :as cli]))
(let [extra-opts '%s
      sym `%s
      the-var (requiring-resolve sym)
      the-var-meta (meta the-var)
      ns (:ns (meta the-var))
      ns-meta (meta ns)
      ct (babashka.tasks/current-task)
      cli-opts (babashka.cli/merge-opts (:org.babashka/cli ns-meta)
                                        (:org.babashka/cli the-var-meta)
                                        (:org.babashka/cli ct)
                                        extra-opts)
      task-exec-args (:exec-args ct)
      cli-exec-args (:exec-args cli-opts)
      exec-args {:exec-args (babashka.cli/merge-opts cli-exec-args task-exec-args)}
      cli-opts (babashka.cli/merge-opts exec-args cli-opts)
      opts (babashka.cli/parse-opts *command-line-args* cli-opts)]
(the-var opts))"
           (random-uuid)
           (pr-str extra-opts)
           sym
           )))
