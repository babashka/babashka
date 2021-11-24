(ns aaaa-this-has-to-be-first.because-patches
  ;; we need pprint loaded first, it patches pprint to not bloat the GraalVM binary
  (:require [babashka.impl.pprint]
            [babashka.impl.protocols] ;; overrides impl for clojure.lang.Namespace
            ))
