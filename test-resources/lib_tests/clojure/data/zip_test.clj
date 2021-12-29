(ns clojure.data.zip-test
  (:require [clojure.test :as t :refer [deftest is]]
            [clojure.data.xml :as xml]
            [clojure.zip :as zip]
            [clojure.data.zip.xml :refer [attr attr= xml1->]]))

(def data (str "<root>"
               "  <character type=\"person\" name=\"alice\" />"
               "  <character type=\"animal\" name=\"march hare\" />"
               "</root>"))

(deftest xml1-test
  (let [xml   (zip/xml-zip (xml/parse (java.io.StringReader. data)))]
    (is (= "person"
         (xml1-> xml :character [(attr= :name "alice")] (attr :type))))
    (is (= "march hare"
         (xml1-> xml :character [(attr= :type "animal")] (attr :name))))))
