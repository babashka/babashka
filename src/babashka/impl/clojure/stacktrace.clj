(ns babashka.impl.clojure.stacktrace
  {:no-doc true}
  (:require [clojure.stacktrace :as stacktrace]
            [sci.core :as sci]))

(def sns (sci/create-ns 'clojure.stacktrace nil))

(def stacktrace-namespace
  {'root-cause (sci/copy-var stacktrace/root-cause sns)
   'print-trace-element (sci/copy-var stacktrace/print-trace-element sns)
   'print-throwable (sci/copy-var stacktrace/print-throwable sns)
   'print-stack-trace (sci/copy-var stacktrace/print-stack-trace sns)
   'print-cause-trace (sci/copy-var stacktrace/print-cause-trace sns)})
