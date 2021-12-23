(ns babashka.impl.rewrite-clj
  {:no-doc true}
  (:require [rewrite-clj.node]
            [rewrite-clj.paredit]
            [rewrite-clj.parser]
            [rewrite-clj.zip]
            [rewrite-clj.zip.subedit]
            [sci.core :as sci]))

(def nns (sci/create-ns 'rewrite-clj.node nil))
(def pens (sci/create-ns 'rewrite-clj.paredit nil))
(def pns (sci/create-ns 'rewrite-clj.parser nil))
(def zns (sci/create-ns 'rewrite-clj.zip nil))
(def zsns (sci/create-ns 'rewrite-clj.zip.subedit nil))

(def node-namespace
  (sci/copy-ns rewrite-clj.node nns))

(def parser-namespace
  (sci/copy-ns rewrite-clj.parser pns))

(def paredit-namespace
  (sci/copy-ns rewrite-clj.paredit pens))

(def zip-namespace
  (sci/copy-ns rewrite-clj.zip zns))

(def subedit-namespace
  (sci/copy-ns rewrite-clj.zip.subedit zsns))
