(ns babashka.impl.reify2
  {:no-doc true}
  (:require [sci.impl.types]
            [insn.core :as insn]
            [insn.op :as op]
            [insn.util :as util])
  (:import [org.objectweb.asm Handle Opcodes MethodVisitor]
           [java.lang.reflect Field Method Modifier]))

(set! *warn-on-reflection* false)


(defn write-super-remove [v]
  (doto v
    (op/aload 0)
    (.visitMethodInsn Opcodes/INVOKESPECIAL (util/class-desc 'java.util.Iterator)
                      (util/method-name "remove") (util/method-desc [:void]) true)
    (op/return)))

(def invoke-super-data
  {:name "my.Invoker"
   :version 8
   :flags [:super :public]
   :interfaces [java.util.Iterator]
   :methods [
             {:flags [:public]
              :name "remove"
              :desc [:void]
              :emit write-super-remove}]})

(defn set-symbol! [s]
  [[:aconst-null]
   [:ldc s]
   [:invokestatic clojure.lang.Symbol "intern" [String String clojure.lang.Symbol]]
   [:putstatic :this (str "_sym_" s) clojure.lang.Symbol]])

(defn return [desc]
  (case (last desc)
    :void [:return]
    (:boolean :int) [:ireturn]
    [:areturn]))

(defn loads [desc]
  (mapv (fn [i]
          [:aload i])
        (range (count desc))))

(defn emit-method [class meth desc]
  (let [args (dec (count desc))]
    (reduce into []
            [[[:aload 0]
              [:getfield :this "_methods" java.util.Map]
              [:getstatic :this (str "_sym_" meth) clojure.lang.Symbol]
              [:invokeinterface java.util.Map "get"]
              [:checkcast clojure.lang.IFn]
              [:astore (inc args)]
              [:aload (inc args)]
              [:ifnull :fallback]
              [:aload (inc args)]]
             (loads desc)
             [[:invokeinterface clojure.lang.IFn "invoke" (vec (repeat (inc (count desc)) Object))]
              (let [ret-type* (last desc)
                    ret-type (if (class? ret-type*)
                               (.getName ^Class ret-type*)
                               ret-type*)]
                (case ret-type
                  :void [:pop]
                  :boolean [[:checkcast Boolean]
                            [:invokevirtual Boolean "booleanValue"]]
                  :int [[:checkcast Integer]
                        [:invokevirtual Integer "intValue"]]
                  "java.lang.Object" nil
                  (when (class? ret-type*)
                    [[:checkcast ret-type*]])))
              (return desc)
              [:mark :fallback]]
             (loads desc)
             [[:invokespecial class meth desc]
              (return desc)]])))

(defn interface-data [^Class interface methods]
  (let [class-sym (symbol (.getName interface))
        method-names (distinct (map :name methods))]
    {:name (symbol (str "babashka.impl." (.getName interface)))
     :version 1.8
     :interfaces [class-sym
                  'clojure.lang.IMeta
                  'clojure.lang.IObj]
     :fields (into [{:flags #{:private},
                     :name "_methods" :type java.util.Map}
                    {:flags #{:private},
                     :name "_meta" :type clojure.lang.IPersistentMap}]
                   (for [name method-names]
                     {:flags #{:private :static},
                      :name (str "_sym_" name) :type clojure.lang.Symbol}))
     :methods (into [{:name :clinit
                      :emit (reduce into
                                    []
                                    (conj
                                     (mapv set-symbol! method-names)
                                     [[:return]]))}
                     {:name :init
                      :desc [:void]
                      :emit [[:aload 0]
                             [:invokespecial :super :init [:void]]
                             [:return]]}
                     {:name :init
                      :desc [java.util.Map :void]
                      :emit [[:aload 0]
                             [:invokespecial :super :init [:void]]
                             [:aload 0]
                             [:aload 1]
                             [:putfield :this "_methods" java.util.Map]
                             [:return]]}
                     {:name :meta
                      :desc [clojure.lang.IPersistentMap]
                      :emit [[:aload 0]
                             [:getfield :this "_meta" clojure.lang.IPersistentMap]
                             [:areturn]]}
                     {:name :withMeta
                      :desc [clojure.lang.IPersistentMap clojure.lang.IObj]
                      :emit [[:aload 0]
                             [:aload 1]
                             [:putfield :this "_meta" clojure.lang.IPersistentMap]
                             [:aload 0]
                             [:areturn]]}]
                    (for [{:keys [name desc]} methods]
                      {:flags #{:public}, :name name
                       :desc desc
                       :emit (emit-method interface name desc)}))}))

(set! *warn-on-reflection* true)

(defn class->methods [^Class clazz]
  (let [meths (mapv bean (.getMethods clazz))
        meths (mapv (fn [{:keys [name
                                 parameterTypes
                                 returnType]}]
                      (let [ret-type (condp = returnType
                                       Void/TYPE :void
                                       Boolean/TYPE :boolean
                                       Integer/TYPE :int
                                       returnType)]
                        {:name name
                         :desc (conj (vec parameterTypes) ret-type)}))
                    meths)]
    (distinct meths)))

(def interfaces [java.nio.file.FileVisitor
                 java.io.FileFilter
                 java.io.FilenameFilter
                 clojure.lang.Associative
                 clojure.lang.ILookup
                 java.util.Map$Entry
                 clojure.lang.IFn
                 clojure.lang.IPersistentCollection
                 clojure.lang.IReduce
                 clojure.lang.IReduceInit
                 clojure.lang.IKVReduce
                 ;; TODO: fix interfaces with primitive arguments
                 #_clojure.lang.Indexed
                 clojure.lang.IPersistentMap
                 clojure.lang.IPersistentStack
                 clojure.lang.Reversible
                 clojure.lang.Seqable
                 java.lang.Iterable
                 ;; TODO: fix interfaces with primitive arguments
                 #_java.net.http.WebSocket$Listener
                 java.util.Iterator
                 java.util.function.Function
                 java.util.function.Supplier
                 java.lang.Comparable
                 javax.net.ssl.X509TrustManager
                 clojure.lang.LispReader$Resolver])

(doseq [i interfaces]
  (insn/define (interface-data i (class->methods i))))

(defn method-or-bust [methods k]
  (or (get methods k)
      (throw (UnsupportedOperationException. "Method not implemented: " k))))

(defmacro gen-reify-fn []
  `(fn [~'m]
     (case (.getName ~(with-meta `(first (:interfaces ~'m))
                        {:tag 'Class}))
      "java.lang.Object"
      (reify java.lang.Object
        (toString [~'this]
          ((method-or-bust (:methods ~'m) 'toString) ~'this)))
      ~@(for [i interfaces]
          [(let [in (.getName ^Class i)]
             `(new ~(symbol (str "babashka.impl." in)) (:methods ~'m)))]))))

(def reify-fn (gen-reify-fn))

#_(def reify-fn
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

      }))
