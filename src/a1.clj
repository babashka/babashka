(ns a1
  ;; we need pprint loaded first, it patches pprint to not bloat the GraalVM binary
  (:require [babashka.impl.pprint]))
