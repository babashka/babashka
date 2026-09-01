(ns clojure.tools.deps.specs
  "Shadows the tools.deps.edn namespace of the same name.

  The real one is built on clojure.spec.alpha, which bb leaves out by default.
  Loading it pulls spec in, and spec in turn makes much more of encore/timbre,
  core.async and tools.analyzer.jvm reachable. tools.deps.edn calls only
  valid-deps? and explain-deps, so stub those and skip validation.")

(defn valid-deps?
  [_deps-edn]
  true)

(defn explain-deps
  [_deps-edn]
  nil)
