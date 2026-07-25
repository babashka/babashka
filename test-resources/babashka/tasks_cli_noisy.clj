(ns babashka.tasks-cli-noisy)

;; Printing at load time: completion candidates and `bb tasks` output must not
;; pick this up when the namespace is loaded to read a spec or a docstring.
(println "LOAD NOISE")

(defn go
  "Runs it"
  {:org.babashka/cli {:spec {:port {:coerce :int :desc "port"}}}}
  [opts]
  (prn (assoc opts :ran :noisy)))
