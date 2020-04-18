(ns babashka.impl.clojure.stacktrace
  {:no-doc true}
  (:require [clojure.stacktrace :as stacktrace]
            [sci.core :as sci]))

(defmacro wrap-out [f]
  `(fn [& ~'args]
     (binding [*out* @sci/out]
       (apply ~f ~'args))))

(def stacktrace-namespace
  {'root-cause stacktrace/root-cause
   'print-trace-element (wrap-out stacktrace/print-trace-element)
   'print-throwable (wrap-out stacktrace/print-throwable)
   'print-stack-trace (wrap-out stacktrace/print-stack-trace)
   'print-cause-trace (wrap-out stacktrace/print-cause-trace)})
