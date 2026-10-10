(ns orchard.stacktrace
  "Stand-in for orchard.stacktrace, used by the debugger."
  (:require [babashka.nrepl.impl.cider :as cider]))

(defn analyze
  "Returns the causes of ex, innermost last."
  [ex]
  (cider/analyze ex))
