(ns babashka.impl.xml
  {:no-doc true}
  (:require [clojure.data.xml :as xml]
            [sci.impl.namespaces :refer [copy-var]]
            [sci.impl.vars :as vars]))

(def xns (vars/->SciNamespace 'clojure.data.xml nil))

(def xml-namespace
  {'aggregate-xmlns (copy-var xml/aggregate-xmlns xns)
   'as-qname        (copy-var xml/as-qname xns)
   'cdata           (copy-var xml/cdata xns)
   'element         (copy-var xml/element xns)
   'element*        (copy-var xml/element* xns)
   'element-nss     (copy-var xml/element-nss xns)
   'element?        (copy-var xml/element? xns)
   'emit            (copy-var xml/emit xns)
   'emit-str        (copy-var xml/emit-str xns)
   'event-seq       (copy-var xml/event-seq xns)
   'find-xmlns      (copy-var xml/find-xmlns xns)
   'indent          (copy-var xml/indent xns)
   'indent-str      (copy-var xml/indent-str xns)
   'parse           (copy-var xml/parse xns)
   'parse-qname     (copy-var xml/parse-qname xns)
   'parse-str       (copy-var xml/parse-str xns)
   'print-uri-file-command! (copy-var xml/print-uri-file-command! xns)
   'qname             (copy-var xml/qname xns)
   'qname-local       (copy-var xml/qname-local xns)
   'qname-uri         (copy-var xml/qname-uri xns)
   'sexp-as-element   (copy-var xml/sexp-as-element xns)
   'sexps-as-fragment (copy-var xml/sexps-as-fragment xns)
   'symbol-uri      (copy-var xml/symbol-uri xns)
   'uri-file        (copy-var xml/uri-file xns)
   'uri-symbol      (copy-var xml/uri-symbol xns)
   'xml-comment     (copy-var xml/xml-comment xns)})
