(ns babashka.impl.markdown
  (:require [nextjournal.markdown]
            [nextjournal.markdown.impl :as impl]
            [nextjournal.markdown.transform]
            [sci.core :as sci]))

@@#'impl/visitChildren-meth

(def mdns (sci/create-ns 'nextjournal.markdown nil))

(def markdown-namespace (sci/copy-ns nextjournal.markdown mdns))

(def mdtns (sci/create-ns 'nextjournal.markdown.transform nil))

(def markdown-transform-namespace (sci/copy-ns nextjournal.markdown.transform mdtns))


