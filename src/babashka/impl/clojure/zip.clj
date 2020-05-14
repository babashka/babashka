(ns babashka.impl.clojure.zip
  {:no-doc true}
  (:require [clojure.zip :as zip]
            [sci.impl.namespaces :refer [copy-var]]
            [sci.impl.vars :as vars]))

(def zip-ns (vars/->SciNamespace 'clojure.zip nil))

(def zip-namespace
  {'zipper       (copy-var zip/zipper zip-ns)
   'seq-zip      (copy-var zip/seq-zip zip-ns)
   'vector-zip   (copy-var zip/vector-zip zip-ns)
   'xml-zip      (copy-var zip/xml-zip zip-ns)
   'node         (copy-var zip/node zip-ns)
   'branch?      (copy-var zip/branch? zip-ns)
   'children     (copy-var zip/children zip-ns)
   'make-node    (copy-var zip/make-node zip-ns)
   'path         (copy-var zip/path zip-ns)
   'lefts        (copy-var zip/lefts zip-ns)
   'rights       (copy-var zip/rights zip-ns)
   'down         (copy-var zip/down zip-ns)
   'up           (copy-var zip/up zip-ns)
   'root         (copy-var zip/root zip-ns)
   'right        (copy-var zip/right zip-ns)
   'rightmost    (copy-var zip/rightmost zip-ns)
   'left         (copy-var zip/left zip-ns)
   'leftmost     (copy-var zip/leftmost zip-ns)
   'insert-left  (copy-var zip/insert-left zip-ns)
   'insert-right (copy-var zip/insert-right zip-ns)
   'replace      (copy-var zip/replace zip-ns)
   'edit         (copy-var zip/edit zip-ns)
   'insert-child (copy-var zip/insert-child zip-ns)
   'append-child (copy-var zip/append-child zip-ns)
   'next         (copy-var zip/next zip-ns)
   'prev         (copy-var zip/prev zip-ns)
   'end?         (copy-var zip/end? zip-ns)
   'remove       (copy-var zip/remove zip-ns)})
