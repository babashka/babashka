(ns nrepl.util.completion
  "Stand-in for nREPL's nrepl.util.completion, which walks JVM namespaces and
  classes. babashka completes against sci's namespaces instead.")

(defn completions
  "Returns a list of completions for the given prefix and namespace."
  ([prefix] (completions prefix *ns* {}))
  ([prefix ns] (completions prefix ns {}))
  ([prefix ns options]
   ((requiring-resolve 'babashka.nrepl.completion/completions) prefix ns options)))
