(ns cider.nrepl.middleware.util.eval
  #_(:import clojure.lang.Compiler)) ;; BB-PATCH no Compiler in sci

;; The sole reason for this namespace to exist is to prevent
;; `cider.nrepl.middleware.debug/instrument-and-eval` from appearing on the
;; stacktrace when we don't, in fact, compile with the debugger. Sure, this may
;; seem minor, but I don't want to confuse users and send them on wild geese
;; chases thinking that the debugger may be somehow related to the thrown
;; exceptions when it is not enabled at all.

(defn eval-dispatcher [debugger-eval-fn dispatch-kw]
  (fn [form]
    (if (get (meta form) dispatch-kw)
      (debugger-eval-fn form)
      #_(Compiler/eval form true) ;; BB-PATCH sci eval
(eval form))))
