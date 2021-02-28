(ns babashka.impl.reify
  {:no-doc true}
  (:require [clojure.math.combinatorics :as combo]))

(set! *warn-on-reflection* false)

(defmacro gen-reify-combos
  "Generates pre-compiled reify combinations"
  [methods]
  (let [subsets (rest (combo/subsets (seq methods)))]
    (reduce (fn [opts classes]
              (assoc opts
                     (set (map (fn [[class _]]
                                 (list 'quote class))
                               classes))
                     (list 'fn ['methods]
                           (list* 'reify
                                  (mapcat
                                   (fn [[clazz methods]]
                                     (cons clazz
                                           (map
                                            (fn [[meth args]]
                                              (list meth args
                                                    (list*
                                                     (list 'get-in 'methods
                                                           [(list 'quote clazz) (list 'quote meth)])
                                                     args)))
                                                     methods)))
                                   classes)))))
            {}
            subsets)))

#_:clj-kondo/ignore
(def reify-opts
  (gen-reify-combos
   {java.nio.file.FileVisitor {preVisitDirectory [this p attrs]
                               postVisitDirectory [this p attrs]
                               visitFile [this p attrs]}
    java.io.FileFilter {accept [this f]}
    java.io.FilenameFilter {accept [this f s]}
    clojure.lang.ILookup {valAt [this k]}
    clojure.lang.IFn {invoke [this k]}}))
