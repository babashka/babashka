(ns babashka.impl.test
  (:require  [babashka.impl.clojure.test :as t]))

(defn macrofy [v]
  (with-meta v {:sci/macro true}))

(def clojure-test-namespace
  {'*load-tests* t/load-tests
   '*stack-trace-depth* t/stack-trace-depth
   '*report-counters* t/report-counters
   '*initial-report-counters* t/initial-report-counters
   '*testing-vars* t/testing-vars
   '*testing-contexts* t/testing-contexts
   '*test-out* t/test-out
   ;; 'with-test-out (macrofy @#'t/with-test-out)
   'file-position t/file-position
   'testing-vars-str t/testing-vars-str
   'testing-contexts-str t/testing-contexts-str
   'inc-report-counter t/inc-report-counter
   'do-report t/do-report
   'try-expr (macrofy @#'t/try-expr)
   ;; assertion macros
   'is (macrofy @#'t/is)
   ;; defining tests
   'with-test (macrofy @#'t/with-test)
   'deftest (macrofy @#'t/deftest)
   'deftest- (macrofy @#'t/deftest-)
   'set-test (macrofy @#'t/set-test)
   ;; fixtures
   ;; TODO
   ;; running tests: low level
   'test-var t/test-var
   ;;'test-vars t/test-vars
   ;;'test-all-vars t/test-all-vars
   ;;'test-ns t/test-ns
   ;; running tests: high level
   ;; 'run-tests t/run-tests
   ;;'run-all-tests t/run-all-tests
   'successful? t/successful?})
