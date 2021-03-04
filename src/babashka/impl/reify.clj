(ns babashka.impl.reify
  {:no-doc true}
  (:require [clojure.math.combinatorics :as combo]
            [sci.impl.types]))

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
                                           (mapcat
                                            (fn [[meth arities]]
                                              (map
                                               (fn [arity]
                                                 (list meth arity
                                                       (list*
                                                        (list 'get-in 'methods
                                                              [(list 'quote clazz) (list 'quote meth)])
                                                        arity)))
                                               arities))
                                            methods)))
                                   classes)))))
            {}
            subsets)))

#_(prn (macroexpand '(gen-reify-combos
                    {java.io.FileFilter {accept [[this f]]}})))

#_:clj-kondo/ignore
(def reify-opts
  (gen-reify-combos
   {sci.impl.types.IReified {getMethods [[this]]
                             getInterfaces [[this]]}
    java.nio.file.FileVisitor {preVisitDirectory [[this p attrs]]
                               postVisitDirectory [[this p attrs]]
                               visitFile [[this p attrs]]}
    java.io.FileFilter {accept [[this f]]}
    java.io.FilenameFilter {accept [[this f s]]}
    clojure.lang.ILookup {valAt [[this k] [this k default]]}
    clojure.lang.IFn {applyTo [[this arglist]]
                      invoke [[this]
                              [this a1]
                              [this a1 a2]
                              [this a1 a2 a3]
                              [this a1 a2 a3 a4]
                              [this a1 a2 a3 a4 a5]
                              [this a1 a2 a3 a4 a5 a6]
                              [this a1 a2 a3 a4 a5 a6 a7]
                              [this a1 a2 a3 a4 a5 a6 a7 a8]
                              [this a1 a2 a3 a4 a5 a6 a7 a8 a9]
                              [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10]
                              [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11]
                              [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12]
                              [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13]
                              [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14]
                              [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15]
                              [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16]
                              [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17]
                              [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18]
                              [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19]
                              [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20]
                              [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 varargs]]}}))
