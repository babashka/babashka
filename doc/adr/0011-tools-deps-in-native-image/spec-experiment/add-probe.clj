#!/usr/bin/env bb
;; Insert a run-time resolve probe at the top of babashka.main/main.
;; Usage: bb add-probe.clj path/to/main.clj
(let [f (first *command-line-args*)
      s (slurp f)
      old "(defn main [& args]\n"
      new (str old "  (when-let [s (System/getenv \"BB_RESOLVE_PROBE\")] (prn :probe (resolve (symbol s))))\n")]
  (assert (clojure.string/includes? s old) "main not found")
  (spit f (clojure.string/replace s old new))
  (println "probe added"))
