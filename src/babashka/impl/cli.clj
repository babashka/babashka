(ns babashka.impl.cli
  (:require
   [babashka.cli]
   [sci.core :as sci]))

(def cns (sci/create-ns 'babashka.cli))

(def exit-fn
  (sci/new-dynamic-var '*exit-fn* babashka.cli/*exit-fn* {:ns cns}))

;; The compiled babashka.cli code reads the Clojure *exit-fn* var; bridge it to
;; the sci var so scripts can rebind babashka.cli/*exit-fn* with `binding`.
(alter-var-root #'babashka.cli/*exit-fn*
                (constantly (fn [m] (@exit-fn m))))

(def cli-namespace
  (assoc (sci/copy-ns babashka.cli cns)
         '*exit-fn* exit-fn))

(defn exec-fn-snippet
  ([sym] (exec-fn-snippet sym nil))
  ([sym extra-opts]
   (format "
(ns exec-%s
  (:require [babashka.cli :as cli]))
(let [extra-opts '%s
      sym `%s
      the-var (requiring-resolve sym)
      _ (when-not the-var
          (throw (ex-info (str \"Could not resolve sym to a function: \" sym) {:babashka/exit 1})))
      the-var-meta (meta the-var)
      ns (:ns (meta the-var))
      ns-meta (meta ns)
      ct (babashka.tasks/current-task)
      exec-args (babashka.cli/merge-opts (:exec-args (:org.babashka/cli ns-meta))
                                         (:exec-args (:org.babashka/cli the-var-meta))
                                         (:exec-args (:org.babashka/cli ct))
                                         (:exec-args ct)
                                         (:exec-args extra-opts))
      cli-opts (babashka.cli/merge-opts (:org.babashka/cli ns-meta)
                                        (:org.babashka/cli the-var-meta)
                                        (:org.babashka/cli ct)
                                        extra-opts)
      cli-opts (assoc cli-opts :exec-args exec-args)
      opts (babashka.cli/parse-opts *command-line-args* cli-opts)]
(the-var opts))"
           (random-uuid)
           (pr-str extra-opts)
           sym
           )))
