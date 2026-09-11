(ns nrepl.util.completion
  "Stand-in for nREPL's nrepl.util.completion, which walks JVM namespaces and
  classes. babashka completes against sci's namespaces instead."
  (:require [babashka.nrepl.impl.sci :as sci-helpers]))

(defn completions
  "Returns a list of completions for the given prefix and namespace."
  ([prefix] (completions prefix *ns* {}))
  ([prefix ns] (completions prefix ns {}))
  ([prefix ns _options]
   (binding [*ns* (or (if (symbol? ns) (find-ns ns) ns) *ns*)]
     (:completions (sci-helpers/completions prefix)))))
