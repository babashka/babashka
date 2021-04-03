(ns babashka.impl.common)

;; placeholder for ctx
(def ctx (volatile! nil))
(def bb-edn (volatile! nil))
(def verbose? (volatile! false))
