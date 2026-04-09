(ns babashka.impl.xml
  {:no-doc true}
  (:require [babashka.impl.common :refer [ctx]]
            [clojure.data.xml :as xml]
            [clojure.data.xml.event :as event]
            [clojure.data.xml.jvm.parse :as jvm-parse]
            [clojure.data.xml.tree :as tree]
            [sci.core :as sci :refer [copy-var]]
            [sci.impl.vars]))

(def xns (sci/create-ns 'clojure.data.xml nil))
(def xens (sci/create-ns 'clojure.data.xml.event nil))
(def xtns (sci/create-ns 'clojure.data.xml.tree nil))
(def xjpns (sci/create-ns 'clojure.data.xml.jvm.parse nil))

(defn- clj-ns-name [ns]
  (cond (instance? sci.lang.Namespace ns) (str ns)
        (keyword? ns) (name ns)
        :else (str ns)))

(defn alias-uri
  "Define a Clojure namespace aliases for xmlns uris.
  This sets up the current namespace for reading qnames denoted with
  Clojure's ::alias/keywords reader feature.

  ## Example
  (alias-uri :D \"DAV:\")
                           ; similar in effect to
  ;; (require '[xmlns.DAV%3A :as D])
                           ; but required namespace is auto-created
                           ; henceforth, shorthand keywords can be used
  {:tag ::D/propfind}
                           ; ::D/propfind will be expanded to :xmlns.DAV%3A/propfind
                           ; in the current namespace by the reader
  ## Clojurescript support
  Currently, namespaces can't be auto-created in Clojurescript.
  Dummy files for aliased uris have to exist. Have a look at `uri-file` and `print-uri-file-command!` to create those."
  {:arglists '([& {:as alias-nss}])}
  [& ans]
  (loop [[a n & rst :as ans] ans]
    (when (seq ans)
      #_(assert (<= (count ans)) (pr-str ans))
      (let [xn (xml/uri-symbol n)
            al (symbol (clj-ns-name a))]
        (sci/eval-form (ctx) `(create-ns (quote ~xn)))
        (sci/eval-form (ctx) `(alias (quote ~al) (quote ~xn)))
        (recur rst)))))

(def xml-namespace
  {'aggregate-xmlns (copy-var xml/aggregate-xmlns xns)
   'alias-uri       (copy-var alias-uri xns {:copy-meta-from clojure.data.xml/alias-uri})
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

(def xml-event-namespace
  {'event-element (copy-var event/event-element xens)
   'event-exit? (copy-var event/event-exit? xens)
   'event-node (copy-var event/event-node xens)
   '->StartElementEvent (copy-var event/->StartElementEvent xens)
   'map->StartElementEvent (copy-var event/map->StartElementEvent xens)
   '->EmptyElementEvent (copy-var event/->EmptyElementEvent xens)
   'map->EmptyElementEvent (copy-var event/map->EmptyElementEvent xens)
   '->EndElementEvent (copy-var event/->EndElementEvent xens)
   'map->EndElementEvent (copy-var event/map->EndElementEvent xens)
   '->CharsEvent (copy-var event/->CharsEvent xens)
   'map->CharsEvent (copy-var event/map->CharsEvent xens)
   '->CDataEvent (copy-var event/->CDataEvent xens)
   'map->CDataEvent (copy-var event/map->CDataEvent xens)
   '->CommentEvent (copy-var event/->CommentEvent xens)
   'map->CommentEvent (copy-var event/map->CommentEvent xens)
   '->QNameEvent (copy-var event/->QNameEvent xens)
   'map->QNameEvent (copy-var event/map->QNameEvent xens)})

(def xml-tree-namespace
  {'seq-tree (copy-var tree/seq-tree xtns)
   'flatten-elements (copy-var tree/flatten-elements xtns)
   'event-tree (copy-var tree/event-tree xtns)})

(def xml-jvm-parse-namespace
  {'string-source (copy-var jvm-parse/string-source xjpns)})
