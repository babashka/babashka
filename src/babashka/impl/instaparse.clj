(ns babashka.impl.instaparse
  (:require
   [instaparse.combinators-source :as source]
   [instaparse.core :as insta]
   [sci.core :as sci]))

(def ins (sci/create-ns 'instaparse.core))

(def instaparse-namespace
  {#_#_'defparser (sci/copy-var insta/defparser ins)
   #_#_'map->Parser (sci/copy-var insta/map->Parser ins)
   'parser (sci/copy-var insta/parser ins)
   #_#_'transform (sci/copy-var insta/transform ins)})

(def sns (sci/create-ns 'instaparse.combinators-source))

(def instaparse.combinators-source-namespace
  {'regexp (sci/copy-var source/regexp sns)})
