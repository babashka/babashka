(ns babashka.impl.repl-test
  (:require
   [babashka.impl.pprint :refer [pprint-namespace]]
   [babashka.impl.repl :refer [start-repl!]]
   [babashka.test-utils :as tu]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is]]
   [sci.core :as sci]
   [sci.impl.opts :refer [init]]
   [sci.impl.vars :as vars]))

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

(defn assert-repl-error [expr expected]
  (is (str/includes?
       (tu/normalize
        (let [sw (java.io.StringWriter.)]
          (sci/binding [sci/out (java.io.StringWriter.)
                        sci/err sw]
            (sci/with-in-str (str expr "\n:repl/quit")
              (repl!)))
          (str sw))) expected)))

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
                     "Don't know how to create ISeq from: java.lang.Long"))

;;;; Scratch

(comment
  )
