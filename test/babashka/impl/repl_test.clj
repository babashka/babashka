(ns babashka.impl.repl-test
  (:require
   [babashka.impl.repl :refer [start-repl!]]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is]]
   [sci.impl.opts :refer [init]]
   [sci.core :as sci]))

(set! *warn-on-reflection* true)

(defn repl! []
  (start-repl! (init {:bindings {(with-meta '*input*
                                   {:sci.impl/deref! true})
                                 (delay [1 2 3])
                                 '*command-line-args*
                                 ["a" "b" "c"]}
                      :env (atom {})})))

(defn assert-repl [expr expected]
  (is (str/includes? (with-out-str
                       (sci/with-in-str (str expr "\n:repl/quit")
                         (repl!))) expected)))

(deftest repl-test
  (assert-repl "(+ 1 2 3)" "6")
  (assert-repl "(defn foo [] (+ 1 2 3)) (foo)" "6")
  (assert-repl "(defn foo [] (+ 1 2 3)) (foo)" "6")
  (assert-repl "1\n(inc *1)" "2")
  (assert-repl "1\n(dec *1)(+ *2 *2)" "2")
  (assert-repl "1\n(dec *1)(+ *2 *2)" "2")
  (assert-repl "*command-line-args*" "[\"a\" \"b\" \"c\"]")
  (assert-repl "*input*" "[1 2 3]"))

;;;; Scratch

(comment
  )
