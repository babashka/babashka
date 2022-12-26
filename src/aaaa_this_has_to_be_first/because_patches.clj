(ns aaaa-this-has-to-be-first.because-patches
  ;; we need pprint loaded first, it patches pprint to not bloat the GraalVM binary
  (:require [babashka.impl.patches.datafy]
            [babashka.impl.pprint]))

;; Enable this for scanning requiring usage:
(def enable-require-scan
  "(do
    (def old-require require)
    (def old-resolve resolve)

    (def our-requiring-resolve (fn [sym]
                                 (let [ns (symbol (namespace sym))]
                                   (old-require ns)
                                   (old-resolve sym))))

    (defn static-requiring-resolve [form _ _]
      (prn :req-resolve form :args (rest form))
      `(let [res# (our-requiring-resolve ~@(rest form))]
         res#))

    (alter-var-root #'requiring-resolve (constantly @#'static-requiring-resolve))
    (doto #'requiring-resolve (.setMacro))

    (defn static-require [& [&form _bindings & syms]]
      (when (meta &form)
        (prn :require &form (meta &form) *file*))
      `(old-require ~@syms))
    (alter-var-root #'require (constantly @#'static-require))
    (doto #'require (.setMacro))

    (alter-var-root #'clojure.core/serialized-require (constantly (fn [& args]
                                                                    (prn :serialized-req args)))))



    (defn static-resolve [& [&form _bindings & syms]]
      (when (meta &form)
        (prn :require &form (meta &form) *file*))
      `(old-resolve ~@syms))
    (alter-var-root #'resolve (constantly @#'static-resolve))
    (doto #'resolve (.setMacro))
")


(when (System/getenv "BABASHKA_REQUIRE_SCAN")
  (load-string enable-require-scan))
;; ---
