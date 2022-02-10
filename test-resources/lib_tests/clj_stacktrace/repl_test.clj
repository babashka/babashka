(ns clj-stacktrace.repl-test
  (:use clojure.test)
  (:use clj-stacktrace.utils)
  (:use clj-stacktrace.repl))

(defmacro with-cascading-exception
  "Execute body in the context of a variable bound to an exception instance
  that includes a caused-by cascade."
  [binding-sym & body]
  `(try (first (lazy-seq (cons (/) nil)))
        (catch Exception e#
          (let [~binding-sym e#]
            ~@body))))

(deftest test-pst
  (with-cascading-exception e
    (is (with-out-str (pst e)))
    (binding [*e e]
      (is (with-out-str (pst))))))

(deftest test-pst-str
  (with-cascading-exception e
    (is (pst-str e))
    (binding [*e e]
      (is (pst-str)))))

(deftest test-pst+
  (with-cascading-exception e
    (is (with-out-str (pst+ e)))
    (binding [*e e]
      (is (with-out-str (pst+))))))
