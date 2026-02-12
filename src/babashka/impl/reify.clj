(ns babashka.impl.reify
  {:no-doc true}
  (:require [sci.impl.types]))

(set! *warn-on-reflection* false)

;; Notes

;; We abandoned the 'one reify object that implements all interfaces' approach
;; due to false positives. E.g. when you would print a reified object, you would
;; get: 'Not implemented: seq', because print-method thought this object was
;; seqable, while in fact, it wasn't.

(defn method-or-bust [methods k]
  (or (get methods k)
      (throw (UnsupportedOperationException. "Method not implemented: " k))))

(defmacro gen-reify-combos
  "Generates pre-compiled reify combinations.
   Returns a vector of [reify-fn supported-interfaces-set]."
  [methods]
  (let [prelude '(reify
                   sci.impl.types.ICustomType
                   (getInterfaces [this]
                     interfaces)
                   (getMethods [this]
                     methods)
                   (getProtocols [this]
                     protocols)
                   (getFields [this]
                     nil))
        supported (set (map resolve (keys methods)))]
    `[~(list 'fn [{:keys '[interfaces methods protocols]}]
            `(cond ~'(empty? interfaces) ~prelude ~'(> (count interfaces)
                   1) (throw (new Exception "Babashka currently does not support reifying more than one interface."))
                   :else
               (case (.getName ~(with-meta '(first interfaces)
                                  {:tag 'Class}))
                 ~@(mapcat
                    (fn [[clazz methods]]
                      (list
                       (str clazz)
                       (concat prelude
                               (cons clazz
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
                                      methods)))))
                    methods))))
      ~supported]))

;; (require 'clojure.pprint)
;; (clojure.pprint/pprint
;;  (macroexpand '(gen-reify-combos {java.nio.file.FileVisitor
;;                                   {preVisitDirectory  [[this p attrs]]
;;                                    postVisitDirectory [[this p attrs]]
;;                                    visitFile          [[this p attrs]]}})))

#_:clj-kondo/ignore
(let [[f ifaces]
      (gen-reify-combos
       {java.lang.Object
        {toString [[this]]}
        java.nio.file.FileVisitor
        {preVisitDirectory  [[this p attrs]]
         postVisitDirectory [[this p attrs]]
         visitFile          [[this p attrs]]
         visitFileFailed    [[this p ex]]}

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

        java.util.Map$Entry
        {getKey [[this]]
         getValue [[this]]}

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
        {reduce [[this f]
                 [this f initial]]}

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

        java.net.http.WebSocket$Listener
        {onBinary [[this ws data last?]]
         onClose [[this ws status-code reason]]
         onError [[this ws error]]
         onOpen [[this ws]]
         onPing [[this ws data]]
         onPong [[this ws data]]
         onText [[this ws data last?]]}

        java.util.Iterator
        {hasNext [[this]]
         next    [[this]]
         remove  [[this]]
         forEachRemaining [[this action]]}

        java.util.function.Function
        {apply [[this t]]}

        java.util.function.Supplier
        {get [[this]]}

        java.lang.Comparable
        {compareTo [[this other]]}

        javax.net.ssl.X509TrustManager
        {checkClientTrusted [[this chain auth-type]]
         checkServerTrusted [[this chain auth-type]]
         getAcceptedIssuers [[this]]}

        clojure.lang.LispReader$Resolver
        {currentNS [[this]]
         resolveClass [[this sym]]
         resolveAlias [[this sym]]
         resolveVar [[this sym]]}

        sun.misc.SignalHandler
        {handle [[this signal]]}})]
  (def reify-fn f)
  (def reify-supported-interfaces ifaces))
