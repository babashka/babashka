#!/usr/bin/env bb

;; usage: version.clj pom.xml 1.0.1

;; pom.xml:
;; <project>
;;   <version></version>
;; </project>

;; prints to stdout:
;; <project>
;;   <version>1.0.1</version>
;; </project>

(ns set-pom-version
  {:author "Michiel Borkent"}
  (:require [clojure.data.xml :as xml]))

(def pom-xml (first *command-line-args*))
(def version (second *command-line-args*))

(def xml (xml/parse-str (slurp pom-xml)))

(defn update-version [elt]
  (if-let [t (:tag elt)]
    (if (= "version" (name t))
      (assoc elt :content version)
      elt)
    elt))

(println
 (xml/emit-str
  (update xml :content
          (fn [contents]
            (map update-version contents)))))

