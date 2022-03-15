(ns babashka.impl.instaparse
  (:require [instaparse.core :as insta]
            [instaparse.combinators-source :as source]
            [sci.core :as sci]))

(def ins (sci/create-ns 'instaparse.core))

(def instaparse-namespace
  {'defparser (sci/copy-var insta/defparser ins)
   'map->Parser (sci/copy-var insta/map->Parser ins)
   'parser (sci/copy-var insta/parser ins)
   'transform (sci/copy-var insta/transform ins)})

(def sns (sci/create-ns 'instaparse.combinators-source))

(def instaparse.combinators-source-namespace
  {'regexp (sci/copy-var source/regexp sns)})
