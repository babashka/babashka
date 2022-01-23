(ns slingshot.support-test
  (:require [clojure.test :refer :all]
            [slingshot.slingshot :refer [throw+ try+]]
            [slingshot.support :refer :all])
  (:import (java.util.concurrent ExecutionException)))

(deftest test-parse-try+
  (let [f parse-try+]
    (is (= [nil nil nil nil] (f ())))

    (is (= ['(1) nil nil nil] (f '(1))))
    (is (= [nil '((catch 1)) nil nil] (f '((catch 1)))))
    (is (= [nil nil '(else 1) nil] (f '((else 1)))))
    (is (= [nil nil nil '(finally 1)] (f '((finally 1)))))

    (is (= ['(1) '((catch 1)) nil nil] (f '(1 (catch 1)))))
    (is (= ['(1) nil '(else 1) nil] (f '(1 (else 1)))))
    (is (= ['(1) nil nil '(finally 1)] (f '(1 (finally 1)))))

    (is (= ['(1) '((catch 1)) nil '(finally 1)]
           (f '(1 (catch 1) (finally 1)))))
    (is (= ['(1) '((catch 1) (catch 2)) nil '(finally 1)]
           (f '(1 (catch 1) (catch 2) (finally 1)))))
    (is (= ['(1) '((catch 1)) '(else 1) nil]
           (f '(1 (catch 1) (else 1)))))
    (is (= ['(1) '((catch 1) (catch 2)) '(else 1) nil]
           (f '(1 (catch 1) (catch 2) (else 1)))))

    (is (= [nil nil '(else 1) '(finally 1)]
           (f '((else 1) (finally 1)))))
    (is (= ['(1) nil '(else 1) '(finally 1)]
           (f '(1 (else 1) (finally 1)))))
    (is (= [nil '((catch 1)) '(else 1) nil]
           (f '((catch 1) (else 1)))))
    (is (= ['(1) '((catch 1)) '(else 1) nil]
           (f '(1 (catch 1) (else 1)))))

    (is (thrown? IllegalArgumentException (f '((catch 1) (1)))))
    (is (thrown? IllegalArgumentException (f '((finally 1) (1)))))
    (is (thrown? IllegalArgumentException (f '((finally 1) (catch 1)))))
    (is (thrown? IllegalArgumentException (f '((finally 1) (finally 2)))))
    (is (thrown? IllegalArgumentException (f '((else 1) (1)))))
    (is (thrown? IllegalArgumentException (f '((else 1) (catch 1)))))
    (is (thrown? IllegalArgumentException (f '((else 1) (else 2)))))))

(defn stack-trace-fn []
  (stack-trace))

;; BB-TEST-PATCH: Returns jdk.internal.reflect.DelegatingMethodAccessorImpl
;; instead of what's expected
#_(deftest test-stack-trace
  (let [{:keys [methodName className]} (-> (stack-trace-fn) first bean)]
    (is (.startsWith ^String methodName "invoke"))
    (is (re-find #"stack_trace_fn" className))))

(deftest test-resolve-local
  (let [a 4]
    (is (= 4 (resolve-local a)))
    (is (nil? (resolve-local b)))))

(deftest test-wrap
  (let [tmessage "test-wrap-1"
        tobject 4
        tcause (Exception.)
        tstack-trace (stack-trace)
        tdata {:object tobject}
        tcontext (assoc tdata
                   :message tmessage
                   :cause tcause
                   :stack-trace tstack-trace)
        tthrowable (wrap tcontext)
        {:keys [message cause data stackTrace]} (bean tthrowable)]
    (is (ex-data tthrowable))
    (is (= [message cause (seq stackTrace) data]
           [tmessage tcause (seq tstack-trace) tdata]))))

(def test-hooked (atom nil))

(deftest test-throw-hook
  (binding [*throw-hook* #(reset! test-hooked %)]
    (throw+ "throw-hook-string")
    (is (= (set (keys @test-hooked))
           (set [:object :message :cause :stack-trace])))
    (is (= "throw-hook-string" (:object @test-hooked))))
  (binding [*throw-hook* (fn [x] 42)]
    (is (= (throw+ "something") 42))))

(def catch-hooked (atom nil))

(defn catch-hook-return [object]
  (fn [x] (assoc x :catch-hook-return object)))

(defn catch-hook-throw [object]
  (fn [x] (assoc x :catch-hook-throw object)))

(deftest test-catch-hook
  (binding [*catch-hook* #(reset! catch-hooked %)]
    (try+ (throw+ "catch-hook-string") (catch string? x x))
    (is (= (set (keys @catch-hooked))
           (set [:object :message :cause :stack-trace :wrapper :throwable])))
    (is (= "catch-hook-string" (:object @catch-hooked))))
  (binding [*catch-hook* (catch-hook-return 42)]
    (is (= 42 (try+ (throw+ "boo") (catch string? x x)))))
  (binding [*catch-hook* (catch-hook-throw (IllegalArgumentException. "bleh"))]
    (is (thrown-with-msg? IllegalArgumentException #"bleh"
                          (try+ (throw+ "boo") (catch string? x x)))))
  (is (= "soup!"
         (try+
          (binding [*catch-hook* (catch-hook-throw "soup!")]
            (try+
             (throw+ "boo")
             (catch string? x x)))
          (catch string? x x)))))
