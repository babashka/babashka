(ns jasentaa.worked-example-1
  (:require
   #?(:clj  [clojure.test :refer :all]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [jasentaa.monad :as m :refer [do*]]
   [jasentaa.position :refer [strip-location]]
   [jasentaa.parser :refer [parse-all]]
   [jasentaa.parser.basic :refer [from-re match]]
   [jasentaa.parser.combinators :refer [token symb separated-by any-of plus optional]]))

; BNF Grammar, based at that described in: 'Getting Started with PyParsing'
; (http://shop.oreilly.com/product/9780596514235.do)
;
;    searchExpr ::= searchAnd [ OR searchAnd ]...
;    searchAnd  ::= searchTerm [ AND searchTerm ]...
;    searchTerm ::= [NOT] ( singleWord | quotedString | '(' searchExpr ')' )

(def digit (from-re #"[0-9]"))
(def letter (from-re #"[a-z]"))
(def alpha-num (any-of letter digit))

(declare search-expr)

(def single-word
  (m/do*
   (w <- (token (plus alpha-num)))
   (m/return (strip-location w))))

(def quoted-string
  (m/do*
   (symb "\"")
   (t <- (plus (any-of digit letter (match " "))))
   (symb "\"")
   (m/return (strip-location t))))

(def bracketed-expr
  (m/do*
   (symb "(")
   (expr <- (token search-expr))
   (symb ")")
   (m/return expr)))

(def search-term
  (m/do*
   (neg <- (optional (symb "not")))
   (term <- (any-of single-word quoted-string bracketed-expr))
   (m/return (if (empty? neg) term (list :NOT term)))))

(def search-and
  (m/do*
   (lst <- (separated-by search-term (symb "and")))
   (m/return (if (= (count lst) 1)
               (first lst)
               (cons :AND lst)))))

(def search-expr
  (m/do*
   (lst <- (separated-by search-and (symb "or")))
   (m/return (if (= (count lst) 1)
               (first lst)
               (cons :OR lst)))))

(deftest check-grammar
  (is (= [:OR [:AND "wood" "blue"] "red"]
         (parse-all search-expr "wood and blue or red")))

  (is (= [:AND "wood" [:OR "blue" "red"]]
         (parse-all search-expr "wood and (blue or red)")))

  (is (= [:AND [:OR "steel" "iron"] "lime green"]
         (parse-all search-expr "(steel or iron) and \"lime green\"")))

  (is (= [:OR [:NOT "steel"] [:AND "iron" "lime green"]]
         (parse-all search-expr "not steel or iron and \"lime green\"")))

  (is (= [:AND [:NOT [:OR  "steel" "iron"]] "lime green"]
         (parse-all search-expr "not(steel or iron) and \"lime green\"")))

  (is (thrown-with-msg?
       #?(:clj java.text.ParseException
          :cljs js/Error)
       #"Failed to parse text at line: 1, col: 7\nsteel iron\n      \^"
       (parse-all search-expr "steel iron")))

  (is (thrown-with-msg?
       #?(:clj java.text.ParseException
          :cljs js/Error)
       #"Unable to parse text"
       (parse-all search-expr ""))))
