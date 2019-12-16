(ns babashka.impl.classes
  {:no-doc true}
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [cheshire.core :as json]))

(defmacro gen-class-map []
  (let [classes-file (slurp (io/resource "classes.edn"))
        classes-edn (edn/read-string classes-file)
        classes (:classes classes-edn)]
    (apply hash-map
           (for [c classes
                 c [(list 'quote c) c]]
             c))))

(def class-map (gen-class-map))


(defn generate-reflection-file
  "Generate reflection.json file"
  [& args]
  (let [entries (vec (for [c (sort (keys class-map))]
                       {:name (str c)
                        :allPublicMethods true
                        :allPublicFields true
                        :allPublicConstructors true}))]
    (spit (or
           (first args)
           "reflection2.json") (json/generate-string entries {:pretty true}))))
