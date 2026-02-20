(ns babashka.impl.repl-test
  (:require
   [babashka.impl.pprint :refer [pprint-namespace]]
   [babashka.impl.repl :refer [start-repl! repl-with-line-reader complete-form? word-at-cursor format-doc enclosing-fn common-prefix compute-tail-tip]]
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
   If an element is :interrupt, throws UserInterruptException with accumulated input.
   If an element is :eof, throws EndOfFileException (simulates Ctrl+D on empty prompt)."
  ^LineReader [lines]
  (let [remaining (atom lines)]
    (reify LineReader
      (^String readLine [_ ^String _prompt]
        (loop [accumulated ""]
          (if-let [line (first @remaining)]
            (do
              (swap! remaining rest)
              (cond
                (= :interrupt line)
                (throw (UserInterruptException. accumulated))
                (= :eof line)
                (throw (EndOfFileException.))
                :else
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

  (testing "multiple forms on one line"
    (assert-jline-repl ["1 2 3"] "3")
    (assert-jline-repl ["[] [] 999"] "999"))

  (testing "incomplete form in buffer gets continuation"
    (assert-jline-repl ["[] [" "1 2]"] "[1 2]"))

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
    (is (not (str/includes? (jline-repl-combined-output ["(+" :interrupt ":repl/exit"])
                            "To exit"))))

  (testing "whitespace before Ctrl+C resets pending state"
    ;; Ctrl+C (warning) -> space+Ctrl+C (resets) -> Ctrl+C (warning again, not exit)
    (let [output (jline-repl-combined-output [:interrupt " " :interrupt :interrupt ":repl/exit"])]
      (is (= 2 (count (re-seq #"To exit" output))))))

  (testing "Ctrl+D on empty line exits immediately"
    (assert-jline-repl-excludes [:eof "42"] "42"))

  (testing "errors are reported"
    (assert-jline-repl-error ["(+ 1 nil)"] "NullPointerException")
    (assert-jline-repl-error ["(/ 1 0)"] "Divide by zero")))

(deftest format-doc-test
  (testing "function with arglists and doc"
    (let [result (format-doc {:ns "clojure.core" :name "map"
                              :arglists '([f] [f coll] [f c1 c2])
                              :doc "Returns a lazy sequence"})]
      (is (str/starts-with? result "https://clojuredocs.org/clojure.core/map\n"))
      (is (not (str/includes? result "--------")))
      (is (str/includes? result "clojure.core/map"))
      (is (str/includes? result "([f] [f coll] [f c1 c2])"))
      (is (str/includes? result "Returns a lazy sequence"))))
  (testing "macro"
    (let [result (format-doc {:ns "clojure.core" :name "when"
                              :arglists '([test & body])
                              :doc "Evaluates test."
                              :macro true})]
      (is (str/starts-with? result "https://clojuredocs.org/clojure.core/when\n"))
      (is (str/includes? result "Macro"))
      (is (str/includes? result "Evaluates test."))))
  (testing "question mark var gets encoded"
    (let [result (format-doc {:ns "clojure.core" :name "nil?"
                              :arglists '([x])
                              :doc "Returns true if x is nil"})]
      (is (str/starts-with? result "https://clojuredocs.org/clojure.core/nil_q\n"))))
  (testing "no url for non-clojure ns shows separator"
    (let [result (format-doc {:ns "user" :name "foo"
                              :arglists '([x])})]
      (is (str/starts-with? result "--------"))
      (is (not (str/includes? result "clojuredocs")))
      (is (str/includes? result "user/foo"))
      (is (str/includes? result "([x])"))))
  (testing "no arglists"
    (let [result (format-doc {:ns "user" :name "x" :doc "A var"})]
      (is (str/includes? result "user/x"))
      (is (str/includes? result "A var"))
      (is (not (str/includes? result "()"))))))

(deftest enclosing-fn-test
  (testing "basic function call"
    (is (= "map" (enclosing-fn "(map " 5)))
    (is (= "+" (enclosing-fn "(+ " 3)))
    (is (= "map" (enclosing-fn "(map x y)" 7))))
  (testing "cursor on function name returns nil"
    (is (nil? (enclosing-fn "(map)" 4)))
    (is (nil? (enclosing-fn "(map" 4))))
  (testing "nested forms"
    (is (= "map" (enclosing-fn "(let [x (map " 13)))
    (is (= "let" (enclosing-fn "(let [x (map y)] " 18))))
  (testing "not a function call"
    (is (nil? (enclosing-fn "[1 2 " 5)))
    (is (nil? (enclosing-fn "{:a " 4))))
  (testing "empty or no parens"
    (is (nil? (enclosing-fn "" 0)))
    (is (nil? (enclosing-fn "map" 3)))
    (is (nil? (enclosing-fn "( " 2)))))

(deftest word-at-cursor-test
  (testing "simple words"
    (is (= ["map" 0] (word-at-cursor "map" 3)))
    (is (= ["ma" 0] (word-at-cursor "map" 2)))
    (is (= ["m" 0] (word-at-cursor "map" 1))))
  (testing "qualified symbols"
    (is (= ["str/join" 1] (word-at-cursor "(str/join" 9)))
    (is (= ["str/" 1] (word-at-cursor "(str/" 5))))
  (testing "after delimiter"
    (is (= ["foo" 1] (word-at-cursor "(foo" 4)))
    (is (= ["bar" 5] (word-at-cursor "(foo bar" 8))))
  (testing "no word"
    (is (nil? (word-at-cursor "(" 1)))
    (is (nil? (word-at-cursor "" 0)))
    (is (nil? (word-at-cursor "foo " 4)))))


(deftest common-prefix-test
  (testing "single string"
    (is (= "foo" (common-prefix ["foo"]))))
  (testing "identical strings"
    (is (= "abc" (common-prefix ["abc" "abc"]))))
  (testing "common prefix"
    (is (= "get-" (common-prefix ["get-in" "get-method" "get-thread-bindings"]))))
  (testing "no common prefix"
    (is (= "" (common-prefix ["abc" "xyz"]))))
  (testing "empty collection"
    (is (= "" (common-prefix []))))
  (testing "nil collection"
    (is (= "" (common-prefix nil))))
  (testing "full match is prefix"
    (is (= "map" (common-prefix ["map" "mapv" "mapcat"])))))

(deftest compute-tail-tip-test
  (testing "single matching candidate"
    (is (= "n" (compute-tail-tip "get-i" ["get-in"]))))
  (testing "multiple candidates with common prefix beyond word"
    (is (= "e" (compute-tail-tip "interleav" ["interleave" "interleave-all"]))))
  (testing "no extension possible"
    (is (= "" (compute-tail-tip "get-" ["get-in" "get-method" "get-thread-bindings"]))))
  (testing "filters non-matching candidates"
    (is (= "ng" (compute-tail-tip "Stri" ["String" "String" "java.lang.String" "java.io.StringWriter"]))))
  (testing "no candidates"
    (is (= "" (compute-tail-tip "xyz" []))))
  (testing "exact match only"
    (is (= "" (compute-tail-tip "map" ["map"])))))

(deftest start-repl-custom-read-test
  (testing "start-repl! with custom :read bypasses jline"
    (let [done (volatile! false)
          output (tu/normalize
                  (sci/with-out-str
                    (start-repl! test-sci-ctx
                                 {:read (fn [_request-prompt request-exit]
                                          (if @done
                                            request-exit
                                            (do (vreset! done true)
                                                (read-string "(+ 1 2)"))))
                                  :init (constantly nil)})))]
      (is (str/includes? output "3")))))

;;;; Scratch

(comment)
