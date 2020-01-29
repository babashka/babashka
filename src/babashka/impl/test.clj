(ns babashka.impl.test
  (:require  [babashka.impl.clojure.test :as t]))

(defn macrofy [v]
  (with-meta v {:sci/macro true}))

(def clojure-test-namespace
  {'do-report t/do-report
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
   'test-vars t/test-vars
   'test-all-vars t/test-all-vars
   'test-ns t/test-ns
   ;; running tests: high level
   'run-tests t/run-tests
   'run-all-tests t/run-all-tests
   'successful? t/successful?})
