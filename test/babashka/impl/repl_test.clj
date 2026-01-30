(ns babashka.impl.repl-test
  (:require
   [babashka.impl.pprint :refer [pprint-namespace]]
   [babashka.impl.repl :refer [start-repl! repl-with-line-reader complete-form?]]
   [babashka.test-utils :as tu]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is testing]]
   [sci.core :as sci]
   [sci.impl.opts :refer [init]]
   [sci.impl.vars :as vars])
  (:import
   [org.jline.reader LineReader EndOfFileException UserInterruptException]))

(set! *warn-on-reflection* true)

;; (vars/bindRoot sci/in *in*)
;; (vars/bindRoot sci/out *out*)
(vars/bindRoot sci/err *err*)

(defn repl! []
  (start-repl! (init {:bindings {'*command-line-args*
                                 ["a" "b" "c"]}
                      :namespaces {'clojure.pprint pprint-namespace}})))

(defn assert-repl [expr expected]
  (is (str/includes? (tu/normalize
                      (sci/with-out-str
                        (sci/with-in-str (str expr "\n:repl/quit")
                          (repl!)))) expected)))

(defmacro assert-repl-error [expr expected]
  `(is (str/includes?
        (tu/normalize
         (let [sw# (java.io.StringWriter.)]
           (sci/binding [sci/out (java.io.StringWriter.)
                         sci/err sw#]
             (sci/with-in-str (str ~expr "\n:repl/quit")
               (repl!)))
           (str sw#))) ~expected)))

(deftest repl-test
  (assert-repl "1" "1")
  (assert-repl "[1 2 3]" "[1 2 3]")
  (assert-repl "()" "()")
  (assert-repl "(+ 1 2 3)" "6")
  (assert-repl "(defn foo [] (+ 1 2 3)) (foo)" "6")
  (assert-repl "(defn foo [] (+ 1 2 3)) (foo)" "6")
  (assert-repl "1\n(inc *1)" "2")
  (assert-repl "1\n(dec *1)(+ *2 *2)" "2")
  (assert-repl "1\n(dec *1)(+ *2 *2)" "2")
  (assert-repl "*command-line-args*" "[\"a\" \"b\" \"c\"]")
  (assert-repl "(read-line)hello" "hello")
  (assert-repl "(read-line)\nhello" "hello")
  (assert-repl-error "(+ 1 nil)" "NullPointerException")
  (assert-repl-error "(/ 1 0) (pst 1)" "Divide by zero\n\tclojure.lang.Numbers")
  (assert-repl-error "(partition (range 5) 3)"
                     "Don't know how to create ISeq from: java.lang.Long")
  (assert-repl "(throw (ex-info \"foo\" {:a (+ 1 2 3)})) (ex-data *e)"
               "{:a 6}"))

;;;; JLine REPL tests

(def ^:private test-sci-ctx
  (init {:bindings {'*command-line-args* ["a" "b" "c"]}
         :namespaces {'clojure.pprint pprint-namespace}}))

(defn mock-line-reader
  "Creates a mock LineReader that simulates JLine's multi-line behavior.
   Accumulates lines until a complete Clojure form is detected.
   Throws EndOfFileException when lines are exhausted.
   If an element is :interrupt, throws UserInterruptException with accumulated input."
  ^LineReader [lines]
  (let [remaining (atom lines)]
    (reify LineReader
      (^String readLine [_ ^String _prompt]
        (loop [accumulated ""]
          (if-let [line (first @remaining)]
            (do
              (swap! remaining rest)
              (if (= :interrupt line)
                (throw (UserInterruptException. accumulated))
                (let [new-accumulated (str accumulated (when-not (str/blank? accumulated) "\n") line)]
                  (if (complete-form? test-sci-ctx new-accumulated)
                    new-accumulated
                    (recur new-accumulated)))))
            (if (str/blank? accumulated)
              (throw (EndOfFileException.))
              accumulated)))))))

(defn jline-repl! [line-reader]
  (repl-with-line-reader test-sci-ctx line-reader nil))

(defn assert-jline-repl [lines expected]
  (is (str/includes?
       (tu/normalize
        (sci/with-out-str
          (jline-repl! (mock-line-reader lines))))
       expected)))

(defn jline-repl-output [lines]
  (tu/normalize
   (sci/with-out-str
     (jline-repl! (mock-line-reader lines)))))

(defn jline-repl-error-output [lines]
  (tu/normalize
   (let [sw (java.io.StringWriter.)]
     (sci/binding [sci/out (java.io.StringWriter.)
                   sci/err sw]
       (jline-repl! (mock-line-reader lines)))
     (str sw))))

(defn jline-repl-combined-output [lines]
  (tu/normalize
   (let [sw (java.io.StringWriter.)]
     (sci/binding [sci/out sw
                   sci/err sw]
       (jline-repl! (mock-line-reader lines)))
     (str sw))))

(defn assert-jline-repl-error [lines expected]
  (is (str/includes? (jline-repl-error-output lines) expected)))

(defn assert-jline-repl-excludes [lines unexpected]
  (is (not (str/includes? (jline-repl-output lines) unexpected))))

(deftest jline-repl-test
  (testing "basic evaluation"
    (assert-jline-repl ["1"] "1")
    (assert-jline-repl ["(+ 1 2 3)"] "6")
    (assert-jline-repl ["[1 2 3]"] "[1 2 3]")
    (assert-jline-repl ["false"] "false")
    (assert-jline-repl ["nil"] "nil"))

  (testing "multi-line input"
    (assert-jline-repl ["(+" "1 2 3)"] "6")
    (assert-jline-repl ["(defn foo []" "(+ 1 2))" "(foo)"] "3"))

  (testing "*1, *2, *3 work"
    (assert-jline-repl ["1" "(inc *1)"] "2")
    (assert-jline-repl ["1" "2" "(+ *1 *2)"] "3"))

  (testing ":repl/exit quits"
    (assert-jline-repl-excludes [":repl/exit" "123"] "123"))

  (testing ":repl/quit quits"
    (assert-jline-repl-excludes [":repl/quit" "456"] "456"))

  (testing "Ctrl+C on empty prompt shows warning"
    (is (str/includes? (jline-repl-combined-output [:interrupt])
                       "To exit, press Ctrl+C again")))

  (testing "Ctrl+C with input does not show warning"
    (is (not (str/includes? (jline-repl-combined-output ["(+" :interrupt "1 2)" ":repl/exit"])
                            "To exit"))))

  (testing "errors are reported"
    (assert-jline-repl-error ["(+ 1 nil)"] "NullPointerException")
    (assert-jline-repl-error ["(/ 1 0)"] "Divide by zero")))

;;;; Scratch

(comment
  )
