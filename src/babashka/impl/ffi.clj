(ns babashka.impl.ffi
  {:no-doc true}
  (:require [babashka.ffi]
            [sci.core :as sci]))

(def tns (sci/create-ns 'babashka.ffi nil))

;; Every public var of the library, so a new one cannot be forgotten here.
;; What must not be public is kept private in the library, and a var with
;; :no-doc or :skip-wiki metadata is left out, as copy-ns does by default.
(def ffi-namespace
  (sci/copy-ns babashka.ffi tns))
