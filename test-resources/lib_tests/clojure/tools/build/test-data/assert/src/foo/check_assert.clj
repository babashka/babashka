(ns foo.check-assert)

(defn f
  [x]
  {:pre [(keyword? x)]}
  x)
