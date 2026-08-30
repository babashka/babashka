(ns ffi-hook-probe
  (:require [babashka.ffi :refer [defcfn]]))

(defcfn good "labs" [:long] :long
  raw-labs
  [x]
  (raw-labs x))

(defcfn ^:private bad "labs" [:long] :long
  raw2
  [unused-param]
  (undefined-symbol 1))

(defcfn multi "pow" [:double :double] :double
  raw-pow
  ([x] (raw-pow x 2.0))
  ([x y] (raw-pow x y)))

(defcfn plain "Doc." {:private true} "labs" [:long] :long)

(defcfn printf* "printf" [:string :&] :int)

(def dyn-args [:long])
(defcfn dyn "Doc2." "labs" dyn-args :long)

(defcfn wrap-sym (unresolved-sym-expr "x") [:long] :long
  raw3
  [x]
  (raw3 x))

(defn calls []
  [(good 1)
   (good 1 2)
   (multi 1 2 3)
   (plain 1 2)
   (printf* "x")
   (printf* "x" 1 2)
   (printf*)
   (dyn 1 2 3)
   (bad 1)])

(defcfn no-args "zlibVersion" [] :string)
(def use-it (no-args))

(defcfn raw-arity "pow" [:double :double] :double
  raw-p
  [x]
  (raw-p x))

(defcfn shadow "labs" [:long] :long
  raw-s
  ([raw-s] raw-s)
  ([a b] (raw-s (+ a b))))

;; The C return value has no static type: none of these is a type mismatch.
(def typed-uses
  [(inc (plain 1))
   (subs (no-args) 0)
   (inc (good 1))])
