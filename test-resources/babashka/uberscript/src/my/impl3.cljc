(ns my.impl3)

;; see https://github.com/juxt/aero/blob/743e9bc495425b4a4a7c780f5e4b09f6680b4e7a/src/aero/core.cljc#L27
;; and https://github.com/juxt/aero/blob/743e9bc495425b4a4a7c780f5e4b09f6680b4e7a/src/aero/impl/macro.cljc#L9
(defmacro usetime
  [& body]
  (when #?(:clj true :cljs (not (re-matches #".*\$macros" (name (ns-name *ns*)))))
    `(do ~@body)))

(usetime
 (defn foo []))
