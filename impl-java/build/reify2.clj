(ns build.reify2
  {:no-doc true}
  (:require [babashka.impl.reify2.interfaces :refer [interfaces]]
            [insn.core :as insn]))

(set! *warn-on-reflection* false)

(defn set-symbol! [s]
  [[:aconst-null]
   [:ldc s]
   [:invokestatic clojure.lang.Symbol "intern" [String String clojure.lang.Symbol]]
   [:putstatic :this (str "_sym_" s) clojure.lang.Symbol]])

(defn return [desc]
  (case (last desc)
    :void [:return]
    (:boolean :int :byte :short :char) [:ireturn]
    :long [:lreturn]
    :float [:freturn]
    :double [:dreturn]
    [:areturn]))

(defn slots [desc]
  (->> (butlast desc)
       (map #(case % (:long :double) 2 1))
       (reductions + 1)))

(defn loads [desc slots cast?]
  (let [desc (butlast desc)]
    (vec
     (mapcat (fn [i e]
               (if-let [[xload boxed]
                        (case e
                          :boolean [:iload Boolean]
                          :int [:iload Integer]
                          :byte [:iload Byte]
                          :short [:iload Short]
                          :char [:iload Character]
                          :long [:lload Long]
                          :float [:fload Float]
                          :double [:dload Double]
                          nil)]
                 [[xload i]
                  (when cast? [:invokestatic boxed "valueOf" [e boxed]])]
                 [[:aload i]]))
             slots
             desc))))

(defn emit-method [class meth desc default]
  (let [slots (slots desc)
        method-slot (last slots)]
    [[[:aload 0]
      [:getfield :this "_methods" java.util.Map]
      [:getstatic :this (str "_sym_" meth) clojure.lang.Symbol]
      [:invokeinterface java.util.Map "get" [Object Object]]
      [:checkcast clojure.lang.IFn]
      [:astore method-slot]
      [:aload method-slot]
      [:ifnull :fallback]
      [:aload method-slot]
      ;; load this, always the first argument of IFn
      [:aload 0]]
     ;; load remaining args
     (loads desc slots true)
     [[:invokeinterface clojure.lang.IFn "invoke" (vec (repeat (inc (count desc)) Object))]
      (let [ret-type* (last desc)
            ret-type (if (class? ret-type*)
                       (.getName ^Class ret-type*)
                       ret-type*)]
        (if-let [[tvalue boxed]
                 (case ret-type
                   :int ["intValue" Integer]
                   :boolean ["booleanValue" Boolean]
                   :byte ["byteValue" Byte]
                   :short ["shortValue" Short]
                   :long ["longValue" Long]
                   :float ["floatValue" Float]
                   :double ["doubleValue" Double]
                   :char ["charValue" Character]
                   nil)]
          [[:checkcast boxed]
           [:invokevirtual boxed tvalue]]
          (case ret-type
            :void [:pop]
            "java.lang.Object" nil
            (when (class? ret-type*)
              [[:checkcast ret-type*]]))))
      (return desc)
      [:mark :fallback]]
     (if default
       [[[:aload 0]]
        (loads desc slots false)
        [[:invokespecial class meth desc true]
         (return desc)]]
       [[:new java.lang.UnsupportedOperationException]
        [:dup]
        [:ldc (format "No implementation of method found: %s %s" meth desc)]
        [:invokespecial java.lang.UnsupportedOperationException :init [String :void]]
        [:athrow]])]))

(defn interface-data [^Class interface methods]
  (let [class-sym (symbol (.getName interface))
        method-names (distinct (map :name methods))]
    {:name (symbol (str "babashka.impl." (.getName interface)))
     :version 1.8
     :interfaces [class-sym
                  'sci.impl.types.ICustomType
                  'clojure.lang.IMeta
                  'clojure.lang.IObj]
     :flags [:super :public]
     :fields (into [{:flags #{:private},
                     :name "_methods" :type java.util.Map}
                    {:flags #{:private},
                     :name "_interfaces" :type Object}
                    {:flags #{:private},
                     :name "_protocols" :type Object}
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
                      :desc [java.util.Map Object Object :void]
                      :emit [[:aload 0]
                             [:invokespecial :super :init [:void]]
                             [:aload 0]
                             [:aload 1]
                             [:putfield :this "_methods" java.util.Map]
                             [:aload 0]
                             [:aload 2]
                             [:putfield :this "_interfaces" Object]
                             [:aload 0]
                             [:aload 3]
                             [:putfield :this "_protocols" Object]
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
                             [:areturn]]}
                     {:name :getInterfaces
                      :desc [Object]
                      :emit [[:aload 0]
                             [:getfield :this "_interfaces" Object]
                             [:areturn]]}
                     {:name :getMethods
                      :desc [Object]
                      :emit [[:aload 0]
                             [:getfield :this "_methods" java.util.Map]
                             [:areturn]]}
                     {:name :getProtocols
                      :desc [Object]
                      :emit [[:aload 0]
                             [:getfield :this "_protocols" Object]
                             [:areturn]]}
                     {:name :getFields
                      :desc [Object]
                      :emit [[:aconst-null]
                             [:areturn]]}]
                    (for [{:keys [name desc default]} methods]
                      {:flags #{:public}, :name name
                       :desc desc
                       :emit (emit-method interface name desc default)}))}))

(set! *warn-on-reflection* true)

(defn type->kw [type]
  (condp = type
    Void/TYPE :void
    Boolean/TYPE :boolean
    Integer/TYPE :int
    Byte/TYPE :byte
    Short/TYPE :short
    Long/TYPE :long
    Float/TYPE :float
    Double/TYPE :double
    Character/TYPE :char
    type))

(defn class->methods [^Class clazz]
  (let [meths (.getMethods clazz)
        meths (mapv bean meths)
        ;; TODO: fix problems with clojure.lang.IFn, special cased for now
        ;; The problem is that the 20-arity (highest one) could not be reified
        ;; meths (filter #(<= (:parameterCount %) 19) meths)
        meths (mapv (fn [{:keys [name
                                 parameterTypes
                                 returnType
                                 default]}]
                      (let [ret-type (type->kw returnType)]
                        {:name name
                         :desc (conj (mapv type->kw parameterTypes) ret-type)
                         :default default}))
                    meths)]
    (distinct meths)))

(let [i clojure.lang.IFn]
  (insn/define (insn/visit (interface-data i (class->methods i)))))

(def reified (babashka.impl.clojure.lang.IFn. {'invoke (fn [& _args] :yep)} {} {}))

(defn gen-reified [i]
  (insn/write (doto (insn/visit (interface-data i (class->methods i)))
                insn/define) "target/classes"))

(defn gen-classes [_]
  (run! gen-reified interfaces))

(comment
  (definterface IHaveManyPrimitives
    (^void example [^byte b ^short s ^int i ^long l ^boolean z ^float f ^double d])
    (^int example2 [^long x])
    (^boolean example3 []))
  (def impl
    (insn/load-type
     (clojure.lang.DynamicClassLoader.)
     (gen-reified IHaveManyPrimitives)))
  )