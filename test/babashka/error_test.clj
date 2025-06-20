(ns babashka.error-test
  {:clj-kondo/config '{:linters {:unresolved-symbol {:exclude [match?]}}}}
  (:require
   [babashka.test-utils :as tu]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is testing]]
   [matcher-combinators.test]))

(defn process-difference [line]
  (-> line str/trimr
      ;; take into account JDK14+ and native image differences
      (str/replace "class clojure.lang" "clojure.lang")
      (str/replace #" \(.*\)$" "")
      (str/replace #"--\d\d\d\d" "")))

(defmacro multiline-equals [s1 s2]
  `(let [lines-s1# (str/split-lines ~s1)
         lines-s2# (str/split-lines ~s2)
         max-lines# (max (count lines-s1#) (count lines-s2#))
         lines-s1# (take max-lines# lines-s1#)
         lines-s2# (take max-lines# lines-s2#)]
     (is (~'match? (map process-difference lines-s1#) (map process-difference lines-s2#)))
     #_(run! (fn [i]
               (let [l1 (get lines-s1 i)
                     l2 (get lines-s2 i)]
                 (if (and l1 l2)
                   (is (= l1 l2)
                       (format "Lines did not match.\nLine: %s\nLeft:  %s\nRight: %s"
                               i (pr-str l1) (pr-str l2)))
                   (is false (format "Out of lines at line: %s.\nLeft:  %s\nRight: %s"
                                     i (pr-str l1) (pr-str l2))))))
             (range max-lines))))

(deftest stacktrace-from-script-test
  (try (tu/bb nil (.getPath (io/file "test" "babashka" "scripts" "divide_by_zero.bb")))
       (catch Exception e
         (let [msg (ex-message e)
               lines (str/split-lines msg)
               lines (drop-while #(not (str/includes? % "clojure.core//")) lines)
               matches [#"clojure\.core/"
                        #"user/foo .*divide_by_zero.bb:2:3"
                        #"user/foo .*divide_by_zero.bb:1:1"
                        #"user/bar .*divide_by_zero.bb:5:3"
                        #"user/bar .*divide_by_zero.bb:4:1"
                        #"user .*divide_by_zero.bb:7:1"]]
           (doseq [[l m] (map vector lines matches)]
             (is (re-find m l)))))))

(deftest stacktrace-from-expr-test
  (let [output (try (tu/bb nil "
(defn foo [] (/ 1 0))
(foo)")
                    (catch Exception e (ex-message e)))]
    (is (str/includes? (tu/normalize output) "----- Stack trace --------------------------------------------------------------
clojure.core// - <built-in>
user/foo       - <expr>:2:14
user/foo       - <expr>:2:1
user           - <expr>:3:1"))))

(deftest context-test
  (let [output (try (tu/bb nil "
(defn foo [] (/ 1 0))
(foo)")
                    (catch Exception e (ex-message e)))]
    (is (str/includes? (tu/normalize output) "----- Context ------------------------------------------------------------------
1: 
2: (defn foo [] (/ 1 0))
                ^--- Divide by zero
3: (foo)"))))

(deftest parse-error-context-test
  (let [output (try (tu/bb nil "{:a}")
                    (catch Exception e (ex-message e)))]
    (is (str/includes? (tu/normalize output) "----- Context ------------------------------------------------------------------
1: {:a}
   ^--- The map literal starting with :a contains 1 form(s)."))))

(deftest jar-error-test
  (let [output (try (tu/bb nil "-cp" (.getPath (io/file "test-resources" "divide_by_zero.jar")) "-e" "(require 'foo)")
                    (catch Exception e (ex-message e)))]
    (is (str/includes? (tu/normalize output) "----- Error --------------------------------------------------------------------
Type:     java.lang.ArithmeticException
Message:  Divide by zero
Location: foo.clj:1:10

----- Context ------------------------------------------------------------------
1: (ns foo) (/ 1 0)
            ^--- Divide by zero

----- Stack trace --------------------------------------------------------------
clojure.core//       - <built-in>
foo                  - foo.clj:1:10
clojure.core/require - <built-in>
user                 - <expr>:1:1"))))

(deftest static-call-test
  #_(let [output (try (tu/bb nil "-e" "File/x")
                    (catch Exception e (ex-message e)))]
    (is (str/includes? (tu/normalize output)
                       "----- Error --------------------------------------------------------------------
Type:     java.lang.IllegalArgumentException
Message:  No matching field found: x for class java.io.File
Location: <expr>:1:1

----- Context ------------------------------------------------------------------
1: File/x
   ^--- No matching field found: x for class java.io.File

----- Stack trace --------------------------------------------------------------
user - <expr>:1:1")))
  (let [output (try (tu/bb nil "-e" "(File/x)")
                    (catch Exception e (ex-message e)))]
    (is (str/includes? (tu/normalize output)
                       "----- Error --------------------------------------------------------------------
Type:     java.lang.IllegalArgumentException
Message:  No matching method x found taking 0 args
Location: <expr>:1:1

----- Context ------------------------------------------------------------------
1: (File/x)
   ^--- No matching method x found taking 0 args

----- Stack trace --------------------------------------------------------------
user - <expr>:1:1"))))


(deftest error-while-macroexpanding-test
  (let [output (try (tu/bb nil "-e"  "(defmacro foo [x] (assoc :foo 1 2) `(do ~x ~x)) (foo 1)")
                    (catch Exception e (ex-message e)))]
    (multiline-equals output
                      "----- Error --------------------------------------------------------------------
Type:     java.lang.ClassCastException
Message:  clojure.lang.Keyword cannot be cast to clojure.lang.Associative
Location: <expr>:1:19
Phase:    macroexpand

----- Context ------------------------------------------------------------------
1: (defmacro foo [x] (assoc :foo 1 2) `(do ~x ~x)) (foo 1)
                     ^--- clojure.lang.Keyword cannot be cast to clojure.lang.Associative

----- Stack trace --------------------------------------------------------------
clojure.core/assoc--5481 - <built-in>
clojure.core/assoc       - <built-in>
user/foo                 - <expr>:1:19
user/foo                 - <expr>:1:1
user                     - <expr>:1:49")))

(deftest error-in-macroexpansion-test
  (let [output (try (tu/bb nil "-e"  "(defmacro foo [x] `(assoc :foo ~x 2)) (foo 1)")
                    (catch Exception e (ex-message e)))]
    (multiline-equals output
                      "----- Error --------------------------------------------------------------------
Type:     java.lang.ClassCastException
Message:  clojure.lang.Keyword cannot be cast to clojure.lang.Associative
Location: <expr>:1:39

----- Context ------------------------------------------------------------------
1: (defmacro foo [x] `(assoc :foo ~x 2)) (foo 1)
                                         ^--- clojure.lang.Keyword cannot be cast to clojure.lang.Associative

----- Stack trace --------------------------------------------------------------
clojure.core/assoc--5481 - <built-in>
clojure.core/assoc       - <built-in>
user                     - <expr>:1:39"))
  (testing "calling a var inside macroexpansion"
    (let [output (try (tu/bb nil "-e"  "(defn quux [] (assoc :foo 1 2)) (defmacro foo [x & xs] `(do (quux) ~x)) (defn bar [] (foo 1)) (bar)")
                      (catch Exception e (ex-message e)))]
      (multiline-equals output
                        "----- Error --------------------------------------------------------------------
Type:     java.lang.ClassCastException
Message:  clojure.lang.Keyword cannot be cast to clojure.lang.Associative
Location: <expr>:1:15

----- Context ------------------------------------------------------------------
1: (defn quux [] (assoc :foo 1 2)) (defmacro foo [x & xs] `(do (quux) ~x)) (defn bar [] (foo 1)) (bar)
                 ^--- clojure.lang.Keyword cannot be cast to clojure.lang.Associative

----- Stack trace --------------------------------------------------------------
clojure.core/assoc--5481 - <built-in>
clojure.core/assoc       - <built-in>
user/quux                - <expr>:1:15
user/quux                - <expr>:1:1
user/bar                 - <expr>:1:73
user                     - <expr>:1:95"))))

(deftest print-exception-data-test
  (testing "output of uncaught ExceptionInfo"
    (let [output (try (tu/bb nil "(let [d {:zero 0 :one 1}] (throw (ex-info \"some msg\" d)))")
                      (catch Exception e (ex-message e)))]
      (multiline-equals output
                        "----- Error --------------------------------------------------------------------
Type:     clojure.lang.ExceptionInfo
Message:  some msg
Data:     {:zero 0, :one 1}
Location: <expr>:1:27

----- Context ------------------------------------------------------------------
1: (let [d {:zero 0 :one 1}] (throw (ex-info \"some msg\" d)))
                             ^--- some msg

----- Stack trace --------------------------------------------------------------
user - <expr>:1:27
user - <expr>:1:1")))

  (testing "output of ordinary Exception"
    (let [output (try (tu/bb nil "(throw (Exception. \"some msg\"))")
                      (catch Exception e (ex-message e)))]
      (multiline-equals output
                        "----- Error --------------------------------------------------------------------
Type:     java.lang.Exception
Message:  some msg
Location: <expr>:1:1

----- Context ------------------------------------------------------------------
1: (throw (Exception. \"some msg\"))
   ^--- some msg

----- Stack trace --------------------------------------------------------------
user - <expr>:1:1"))))

(deftest debug-exception-print-test
  (testing "debug mode includes locals and exception data in output"
    (let [output (try (tu/bb nil "--debug" "(let [x 1] (/ x 0))")
                      (is false) ; ensure that exception is thrown and we don't get here
                      (catch Exception e (ex-message e)))
          actual-lines (str/split-lines (tu/normalize output))]
      (is (match? (take 16 actual-lines)
                  (str/split-lines "----- Error --------------------------------------------------------------------
Type:     java.lang.ArithmeticException
Message:  Divide by zero
Location: <expr>:1:12

----- Context ------------------------------------------------------------------
1: (let [x 1] (/ x 0))
              ^--- Divide by zero

----- Stack trace --------------------------------------------------------------
clojure.core// - <built-in>
user           - <expr>:1:12
user           - <expr>:1:1

----- Exception ----------------------------------------------------------------
clojure.lang.ExceptionInfo: Divide by zero")))
      (is (str/includes? (nth actual-lines 16)
                         "{:type :sci/error, :line 1, :column 12, :message \"Divide by zero\",")))))

(deftest macro-test
  (let [output (try (tu/bb nil "--debug" "(defmacro foo [x] (assoc :foo 1 2) `(do ~x ~x)) (foo 1)")
                    (is false)
                    (catch Exception e (ex-message e)))
        output (tu/normalize output)
        actual-lines (str/join "\n" (take 19 (str/split-lines output)))]
    (multiline-equals actual-lines
                      "----- Error --------------------------------------------------------------------
Type:     java.lang.ClassCastException
Message:  clojure.lang.Keyword cannot be cast to clojure.lang.Associative
Location: <expr>:1:19
Phase:    macroexpand

----- Context ------------------------------------------------------------------
1: (defmacro foo [x] (assoc :foo 1 2) `(do ~x ~x)) (foo 1)
                     ^--- clojure.lang.Keyword cannot be cast to clojure.lang.Associative

----- Stack trace --------------------------------------------------------------
clojure.core/assoc--5481 - <built-in>
clojure.core/assoc       - <built-in>
user/foo                 - <expr>:1:19
user/foo                 - <expr>:1:1
user                     - <expr>:1:49

----- Exception ----------------------------------------------------------------
clojure.lang.ExceptionInfo: clojure.lang.Keyword cannot be cast to clojure.lang.Associative")))

(deftest native-stacktrace-test
  (let [output (try (tu/bb nil "(merge 1 2 3)")
                    (is false)
                    (catch Exception e (ex-message e)))]
    (is (str/includes? (tu/normalize output)
                       "clojure.core/reduce1        - <built-in>"))))
