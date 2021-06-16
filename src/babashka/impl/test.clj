(ns babashka.impl.test
  (:require [babashka.impl.clojure.test :as t]
            [babashka.impl.common :refer [ctx]]
            [sci.core :as sci]))

(defn contextualize [f]
  (fn [& args]
    (apply f @ctx args)))

(def tns t/tns)

(def clojure-test-namespace
  {:obj tns
   '*load-tests* t/load-tests
   '*stack-trace-depth* t/stack-trace-depth
   '*report-counters* t/report-counters
   '*initial-report-counters* t/initial-report-counters
   '*testing-vars* t/testing-vars
   '*testing-contexts* t/testing-contexts
   '*test-out* t/test-out
   ;; 'with-test-out (macrofy @#'t/with-test-out)
   ;; 'file-position t/file-position
   'testing-vars-str t/testing-vars-str
   'testing-contexts-str t/testing-contexts-str
   'inc-report-counter t/inc-report-counter
   'report t/report
   'do-report t/do-report
   ;; assertion utilities
   'function? t/function?
   'assert-predicate t/assert-predicate
   'assert-any t/assert-any
   ;; assertion methods
   'assert-expr t/assert-expr
   'try-expr (sci/copy-var t/try-expr tns)
   ;; assertion macros
   'is (sci/copy-var t/is tns)
   'are (sci/copy-var t/are tns)
   'testing (sci/copy-var t/testing tns)
   ;; defining tests
   'with-test (sci/copy-var t/with-test tns)
   'deftest (sci/copy-var t/deftest tns)
   'deftest- (sci/copy-var t/deftest- tns)
   'set-test (sci/copy-var t/set-test tns)
   ;; fixtures
   'use-fixtures t/use-fixtures
   'compose-fixtures t/compose-fixtures
   'join-fixtures t/join-fixtures
   ;; running tests: low level
   'test-var t/test-var
   'test-vars t/test-vars
   'test-all-vars (contextualize t/test-all-vars)
   'test-ns (contextualize t/test-ns)
   ;; running tests: high level
   'run-tests (contextualize t/run-tests)
   'run-all-tests (contextualize t/run-all-tests)
   'successful? t/successful?})
