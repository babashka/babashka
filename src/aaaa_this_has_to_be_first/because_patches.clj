(ns aaaa-this-has-to-be-first.because-patches
  ;; we need pprint loaded first, it patches pprint to not bloat the GraalVM binary
  (:require [babashka.impl.patches.datafy]
            [babashka.impl.pprint]))

;; Enable this for scanning requiring-resolve usage:
;; ---
;; (def old-requiring-resolve requiring-resolve)

;; (defmacro static-requiring-resolve [sym]
;;   (prn :sym sym)
;;   `(old-requiring-resolve ~sym))

;; (alter-var-root #'requiring-resolve (constantly @#'static-requiring-resolve))
;; (doto #'requiring-resolve (.setMacro))
;; ---

;; ((requiring-resolve 'clojure.pprint/pprint) (range 20))

;; Enable this for detecting literal usages of require
;; ---
;; (def old-require require)

;; (defmacro static-require [& syms]
;;   (when (meta &form)
;;     (prn :require &form ))
;;   `(old-require ~@syms))
;; (alter-var-root #'require (constantly @#'static-require))
;; (doto #'require (.setMacro))
;; ---
