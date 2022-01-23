(ns babashka.impl.clojure.stacktrace
  {:no-doc true}
  (:require [clojure.stacktrace :as stacktrace]
            [sci.core :as sci]))

(def sns (sci/create-ns 'clojure.stacktrace nil))

(defmacro wrap-out [f]
  `(fn [& ~'args]
     (binding [*out* @sci/out]
       (apply ~f ~'args))))

(defn new-var [var-sym f]
  (sci/new-var var-sym f {:ns sns}))

(def stacktrace-namespace
  {'root-cause (sci/copy-var stacktrace/root-cause sns)
   'print-trace-element (new-var 'print-trace-element (wrap-out stacktrace/print-trace-element))
   'print-throwable (new-var 'print-throwable (wrap-out stacktrace/print-throwable))
   'print-stack-trace (new-var 'print-stack-trace (wrap-out stacktrace/print-stack-trace))
   'print-cause-trace (new-var 'print-cause-trace (wrap-out stacktrace/print-cause-trace))})
