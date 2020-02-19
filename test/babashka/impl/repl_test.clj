(ns babashka.impl.repl-test
  (:require
   [babashka.impl.repl :refer [start-repl!]]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is]]
   [sci.impl.opts :refer [init]]
   [sci.core :as sci]
   [sci.impl.vars :as vars]))

(set! *warn-on-reflection* true)

;; (vars/bindRoot sci/in *in*)
;; (vars/bindRoot sci/out *out*)
(vars/bindRoot sci/err *err*)

(defn repl! []
  (sci/with-bindings {vars/current-ns (vars/->SciNamespace 'user nil)}
    (start-repl! (init {:bindings {'*command-line-args*
                                   ["a" "b" "c"]}
                        :env (atom {})}))))

(defn assert-repl [expr expected]
  (is (str/includes? (sci/with-out-str
                       (sci/with-in-str (str expr "\n:repl/quit")
                         (repl!))) expected)))

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
  (assert-repl "*command-line-args*" "[\"a\" \"b\" \"c\"]"))

;;;; Scratch

(comment
  )
