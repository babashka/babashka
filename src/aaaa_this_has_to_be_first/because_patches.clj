(ns aaaa-this-has-to-be-first.because-patches
  ;; we need pprint loaded first, it patches pprint to not bloat the GraalVM binary
  (:require [babashka.impl.patches.datafy]
            [babashka.impl.pprint]))
