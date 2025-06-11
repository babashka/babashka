(ns babashka.impl.markdown
  (:require
   [nextjournal.markdown]
   [nextjournal.markdown.utils]
   [sci.core :as sci]))

(def markdown-namespace (sci/copy-ns nextjournal.markdown
                                     (sci/create-ns 'nextjournal.markdown nil)))

(def markdown-utils-namespace (sci/copy-ns nextjournal.markdown.utils
                                           (sci/create-ns 'nextjournal.markdown.utils)))

