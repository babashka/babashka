(ns orchard.test.util
  (:require [clojure.string :as str]
            [clojure.template :as template]
            [clojure.test :refer [is]]
            [matcher-combinators.test]))

;; matcher-combinators.test is needed for `match?`

(defmacro with-out-str-rn
  "Like `with-out-str`, but replaces Windows' CR+LF with LF."
  [& body]
  `(let [s# (with-out-str ~@body)]
     (str/replace s# "\r\n" "\n")))

(defmacro is+
  "Like `is` but wraps expected value in matcher-combinators's `match?`."
  ([expected actual]
   `(is+ ~expected ~actual nil))
  ([expected actual message]
   `(is (~'match? ~expected ~actual) ~message)))

(defmacro are*
  "Like `are` but doesn't wrap test expression in `is`, instead allowing the user to use `is+` or any other assertion code."
  [argv expr & args]
  `(template/do-template ~argv ~expr ~@args))
