(ns babashka.impl.utils
  {:no-doc true}
  (:require
   [sci.impl.vars :as vars]
   [sci.core :as sci]))

(sci.impl.vars/bindRoot sci/in *in*)
(sci.impl.vars/bindRoot sci/out *out*)
(sci.impl.vars/bindRoot sci/err *err*)

(defn eval-string [expr ctx]
  (sci/eval-string expr ctx))
