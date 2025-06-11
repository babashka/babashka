(ns babashka.impl.markdown
  (:require [nextjournal.markdown]
            [nextjournal.markdown.impl :as impl]
            [nextjournal.markdown.transform]
            [nextjournal.markdown.utils]
            [nextjournal.markdown.utils.emoji]
            [sci.core :as sci]))

@@#'impl/visitChildren-meth

(def markdown-namespace (sci/copy-ns nextjournal.markdown
                                     (sci/create-ns 'nextjournal.markdown nil)))

(def markdown-utils-namespace (sci/copy-ns nextjournal.markdown.utils
                                           (sci/create-ns 'nextjournal.markdown.utils)))

