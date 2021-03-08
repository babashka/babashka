(ns babashka.impl.reify
  {:no-doc true}
  (:require [sci.impl.types]))

(set! *warn-on-reflection* false)

(defmacro gen-reify-combos
  "Generates pre-compiled reify combinations"
  [methods]
  (let [prelude ['reify
                 'sci.impl.types.IReified
                 '(getInterfaces [this]
                                 interfaces)
                 '(getMethods [this]
                              methods)
                 '(getProtocols [this]
                                protocols)
                 'java.lang.Object
                 '(toString [this]
                            (if-let [m (get methods 'toString)]
                              (m this)
                              (str (.. this getClass getName)
                                   "@" (Integer/toHexString (.hashCode this)))))]]
    (list 'fn [{:keys '[interfaces methods protocols]}]
          (concat prelude
                  (mapcat (fn [[clazz methods]]
                            (cons
                             clazz
                             (mapcat
                              (fn [[meth arities]]
                                (map
                                 (fn [arity]
                                   (list meth arity
                                         (list*
                                          (list 'or (list 'get 'methods (list 'quote meth))
                                                `(throw (new Exception (str "Not implemented: "
                                                                            ~(str meth)))))
                                          arity)))
                                 arities))
                                   methods)))
                          methods)))))

#_:clj-kondo/ignore
(def reify-fn
  (gen-reify-combos
    {java.nio.file.FileVisitor
     {preVisitDirectory  [[this p attrs]]
      postVisitDirectory [[this p attrs]]
      visitFile          [[this p attrs]]}

     java.io.FileFilter
     {accept [[this f]]}

     java.io.FilenameFilter
     {accept [[this f s]]}

     clojure.lang.Associative
     {containsKey [[this k]]
      entryAt     [[this k]]
      assoc       [[this k v]]}

     clojure.lang.ILookup
     {valAt [[this k] [this k default]]}

     clojure.lang.IFn
     {applyTo [[this arglist]]
      invoke  [[this]
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
               [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 varargs]]}

     clojure.lang.IPersistentCollection
     {count [[this]]
      cons  [[this x]]
      empty [[this]]
      equiv [[this x]]}

     clojure.lang.IReduce
     {reduce [[this f]]}

     clojure.lang.IReduceInit
     {reduce [[this f initial]]}

     clojure.lang.IKVReduce
     {kvreduce [[this f initial]]}

     clojure.lang.Indexed
     {nth [[this n] [this n not-found]]}

     clojure.lang.IPersistentMap
     {assocEx [[this k v]]
      without [[this k]]}

     clojure.lang.IPersistentStack
     {peek [[this]]
      pop  [[this]]}

     clojure.lang.Reversible
     {rseq [[this]]}

     clojure.lang.Seqable
     {seq [[this]]}

     java.lang.Iterable
     {iterator [[this]]
      forEach  [[this action]]}

     java.util.Iterator
     {hasNext [[this]]
      next    [[this]]}}))
