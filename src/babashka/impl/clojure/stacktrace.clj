(ns babashka.impl.clojure.stacktrace
  {:no-doc true}
  (:require [clojure.stacktrace :as stacktrace]))

(def stacktrace-namespace
  {'root-cause stacktrace/root-cause
   'print-trace-element stacktrace/print-trace-element
   'print-throwable stacktrace/print-throwable
   'print-stack-trace stacktrace/print-stack-trace
   'print-cause-trace stacktrace/print-cause-trace})
