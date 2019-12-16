(ns babashka.impl.utils
  {:no-doc true}
  (:require [sci.core :as sci]))

(defn eval-string [expr ctx]
  (sci/with-bindings {sci/out *out*
                      sci/in *in*
                      sci/err *err*}
    (sci/eval-string expr ctx)))
