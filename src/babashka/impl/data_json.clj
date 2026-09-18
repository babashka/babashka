(ns babashka.impl.data-json
  {:no-doc true}
  (:require [babashka.impl.pprint]
            [clojure.data.json :as json]
            [sci.core :as sci]))

(def dns (sci/create-ns 'clojure.data.json nil))

(defn pprint
  [x & opts]
  (binding [*out* @sci/out]
    (apply json/pprint x opts)))

(defn pprint-json
  [x & opts]
  (binding [*out* @sci/out]
    (apply json/pprint-json x opts)))

(def data-json-namespace
  (assoc (sci/copy-ns clojure.data.json dns)
         'pprint (sci/copy-var pprint dns {:copy-meta-from clojure.data.json/pprint})
         'pprint-json (sci/copy-var pprint-json dns {:copy-meta-from clojure.data.json/pprint-json})
         'write-string (sci/copy-var* #'clojure.data.json/write-string dns)))
