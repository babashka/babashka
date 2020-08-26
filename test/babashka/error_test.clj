(ns babashka.error-test
  (:require
   [babashka.test-utils :as tu]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is]]))

(defn multiline-equals [s1 s2]
  (let [lines-s1 (str/split-lines s1)
        lines-s2 (str/split-lines s2)
        max-lines (max (count lines-s1) (count lines-s2))]
    (run! (fn [i]
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
    (is (str/includes? output "----- Stack trace --------------------------------------------------------------
clojure.core// - <built-in>
user/foo       - <expr>:2:14
user/foo       - <expr>:2:1
user           - <expr>:3:1"))))

(deftest context-test
  (let [output (try (tu/bb nil "
(defn foo [] (/ 1 0))
(foo)")
                    (catch Exception e (ex-message e)))]
    (is (str/includes? output "----- Context ------------------------------------------------------------------
1: 
2: (defn foo [] (/ 1 0))
                ^--- Divide by zero
3: (foo)"))))

(deftest parse-error-context-test
  (let [output (try (tu/bb nil "{:a}")
                    (catch Exception e (ex-message e)))]
    (is (str/includes? output "----- Context ------------------------------------------------------------------
1: {:a}
   ^--- The map literal starting with :a contains 1 form(s)."))))

(deftest jar-error-test
  (let [output (try (tu/bb nil "-cp" (.getPath (io/file "test-resources" "divide_by_zero.jar")) "-e" "(require 'foo)")
                    (catch Exception e (ex-message e)))]
    (is (str/includes? output "----- Error --------------------------------------------------------------------
Type:     java.lang.ArithmeticException
Message:  Divide by zero
Location: foo.clj:1:10

----- Context ------------------------------------------------------------------
1: (ns foo) (/ 1 0)
            ^--- Divide by zero

----- Stack trace --------------------------------------------------------------
clojure.core// - <built-in>
foo            - foo.clj:1:10"))))

(deftest static-call-test
  (let [output (try (tu/bb nil "-e" "File/x")
                    (catch Exception e (ex-message e)))]
    (is (str/includes? output
                       "----- Error --------------------------------------------------------------------
Type:     java.lang.IllegalArgumentException
Message:  No matching field found: x for class java.io.File
Location: <expr>:1:1

----- Context ------------------------------------------------------------------
1: File/x
   ^--- No matching field found: x for class java.io.File

----- Stack trace --------------------------------------------------------------
user - <expr>:1:1"))
    (let [output (try (tu/bb nil "-e" "(File/x)")
                      (catch Exception e (ex-message e)))]
      (is (str/includes? output
                         "----- Error --------------------------------------------------------------------
Type:     java.lang.IllegalArgumentException
Message:  No matching method x found taking 0 args
Location: <expr>:1:1

----- Context ------------------------------------------------------------------
1: (File/x)
   ^--- No matching method x found taking 0 args

----- Stack trace --------------------------------------------------------------
user - <expr>:1:1")))))


(deftest error-while-macroexpanding-test
  (let [output (try (tu/bb nil "-e"  "(defmacro foo [x] (subs nil 1) `(do ~x ~x)) (foo 1)")
                    (catch Exception e (ex-message e)))]
    (multiline-equals output
                      "----- Error --------------------------------------------------------------------
Type:     java.lang.NullPointerException
Location: <expr>:1:19
Phase:    macroexpand

----- Context ------------------------------------------------------------------
1: (defmacro foo [x] (subs nil 1) `(do ~x ~x)) (foo 1)
                     ^--- 

----- Locals -------------------------------------------------------------------
&form: (foo 1)
&env:  {}
x:     1

----- Stack trace --------------------------------------------------------------
clojure.core/subs - <built-in>
user/foo          - <expr>:1:19
user/foo          - <expr>:1:1
user              - <expr>:1:45")))

(deftest error-in-macroexpansion-test
  (let [output (try (tu/bb nil "-e"  "(defmacro foo [x & xs] `(do (subs nil 1) ~x)) (foo 1)")
                    (catch Exception e (ex-message e)))]
    (multiline-equals output
                      "----- Error --------------------------------------------------------------------
Type:     java.lang.NullPointerException
Location: <expr>:1:47

----- Context ------------------------------------------------------------------
1: (defmacro foo [x & xs] `(do (subs nil 1) ~x)) (foo 1)
                                                 ^--- 

----- Stack trace --------------------------------------------------------------
clojure.core/subs - <built-in>")))
