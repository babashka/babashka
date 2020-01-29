(ns babashka.impl.test
  (:require  [babashka.impl.clojure.test :as t]))

(defn macrofy [v]
  (with-meta v {:sci/macro true}))

(defn contextualize [v]
  (with-meta v {:sci.impl/op :needs-ctx}))

(def clojure-test-namespace
  {'*load-tests* t/load-tests
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
   'try-expr (with-meta @#'t/try-expr
               {:sci/macro true})
   ;; assertion macros
   'is (with-meta @#'t/is
         {;; :sci.impl/op :needs-ctx
          :sci/macro true})
   'are (macrofy @#'t/are)
   'testing (macrofy @#'t/testing)
   ;; defining tests
   'with-test (macrofy @#'t/with-test)
   'deftest (macrofy @#'t/deftest)
   'deftest- (macrofy @#'t/deftest-)
   'set-test (macrofy @#'t/set-test)
   ;; fixtures
   'use-fixtures t/use-fixtures
   'compose-fixtures t/compose-fixtures
   'join-fixtures t/join-fixtures
   ;; running tests: low level
   'test-var t/test-var
   'test-vars t/test-vars
   'test-all-vars (with-meta t/test-all-vars {:sci.impl/op :needs-ctx})
   'test-ns (with-meta t/test-ns {:sci.impl/op :needs-ctx})
   ;; running tests: high level
   'run-tests (contextualize t/run-tests)
   'run-all-tests (contextualize t/run-all-tests)
   'successful? t/successful?})
