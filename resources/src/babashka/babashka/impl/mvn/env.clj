(ns babashka.impl.mvn.env
  "The environment of the resolve. A resolve can run with an environment
  of its own, given through :env and :extra-env on babashka.deps/add-deps,
  so the process environment is only the default."
  {:no-doc true})

(def getenv
  "Returns the value of the environment variable name, or nil."
  (fn [name] (System/getenv name)))
