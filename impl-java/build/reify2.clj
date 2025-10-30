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
    (:boolean :int) [:ireturn]
    :long [:lreturn]
    :float [:freturn]
    :double [:dreturn]
    [:areturn]))

(defn loads [desc slots cast?]
  (let [params (butlast desc)]
    (vec
     (mapcat
      (fn [idx e]
        (case e
          :boolean (if cast?
                     [[:iload idx] [:invokestatic Boolean "valueOf" [:boolean Boolean]]]
                     [[:iload idx]])
          :int (if cast?
                 [[:iload idx] [:invokestatic Integer "valueOf" [:int Integer]]]
                 [[:iload idx]])
          :long (if cast?
                  [[:lload idx] [:invokestatic Long "valueOf" [:long Long]]]
                  [[:lload idx]])
          :float (if cast?
                   [[:fload idx] [:invokestatic Float "valueOf" [:float Float]]]
                   [[:fload idx]])
          :double (if cast?
                    [[:dload idx] [:invokestatic Double "valueOf" [:double Double]]]
                    [[:dload idx]])
          [[:aload idx]]))
      slots
      params))))

#_(defn loads [desc cast?]
  (let [desc (butlast desc)]
    (loop [idx 1
           rem desc
           result []]
      (if (empty? rem)
        result
        (let [e (first rem)
              ;; instruction vector and slot increment
              [ins slot-incr] (case e
                                 :boolean [ [[:iload idx]] 1 ]
                                 :int     [ [[:iload idx]] 1 ]
                                 :long    [ [[:lload idx]] 2 ]
                                 :float   [ [[:fload idx]] 1 ]
                                 :double  [ [[:dload idx]] 2 ]
                                 [ [[:aload idx]] 1 ])
              ;; add boxing if requested
              ins (if (and cast? (#{:boolean :int :long :float :double} e))
                    (conj ins
                          (case e
                            :boolean [:invokestatic Boolean "valueOf" [:boolean Boolean]]
                            :int     [:invokestatic Integer "valueOf" [:int Integer]]
                            :long    [:invokestatic Long "valueOf" [:long Long]]
                            :float   [:invokestatic Float "valueOf" [:float Float]]
                            :double  [:invokestatic Double "valueOf" [:double Double]]))
                    ins)]
          (recur (+ idx slot-incr) (rest rem) (into result ins)))))))


#_(defn loads [desc cast?]
  (let [desc (butlast desc)]
    (loop [idx 1
           rem desc
           result []]
      (if (empty? rem)
        result
        (let [e (first rem)
              [ins slot-incr] (case e
                                 :boolean [[:iload idx]
                                           1]
                                 :int [[:iload idx]
                                       1]
                                 :long [[:lload idx]
                                        2]
                                 :float [[:fload idx]
                                         1]
                                 :double [[:dload idx]
                                          2]
                                 [[[:aload idx]] 1])
              ;; add boxing if requested
              ins (if (and cast? (#{:boolean :int :long :float :double} e))
                    (conj ins
                          (case e
                            :boolean [:invokestatic Boolean "valueOf" [:boolean Boolean]]
                            :int [:invokestatic Integer "valueOf" [:int Integer]]
                            :long [:invokestatic Long "valueOf" [:long Long]]
                            :float [:invokestatic Float "valueOf" [:float Float]]
                            :double [:invokestatic Double "valueOf" [:double Double]]))
                    ins)]
          (recur (+ idx slot-incr) (rest rem) (into result ins)))))))


#_(defn loads [desc cast?]
  (let [desc (butlast desc)]
    (vec
     (mapcat
      (fn [i e]
        (case e
          :boolean [[:iload i]
                    (when cast? [:invokestatic Boolean "valueOf" [:boolean Boolean]])]
          :int [[:iload i]
                (when cast? [:invokestatic Integer "valueOf" [:int Integer]])]
          :long [[:lload i]
                 (when cast? [:invokestatic Long "valueOf" [:long Long]])]
          :float [[:fload i]
                  (when cast? [:invokestatic Float "valueOf" [:float Float]])]
          :double [[:dload i]
                   (when cast? [:invokestatic Double "valueOf" [:double Double]])]
          [[:aload i]]))  ;; default for reference types
      (range 1 (inc (count desc)))
      desc))))

(defn param-slots [desc]
  (let [params (butlast desc)]
    (loop [slots []
           idx 1
           rem params]
      (if (empty? rem)
        slots
        (let [e (first rem)
              slot-count (case e
                           (:long :double) 2
                           1)]
          (recur (conj slots idx)
                 (+ idx slot-count)
                 (rest rem)))))))

(defn emit-method [class meth desc default]
  (let [args (dec (count desc))
        slots (param-slots desc)]
    [[[:aload 0]
      [:getfield :this "_methods" java.util.Map]
      [:getstatic :this (str "_sym_" meth) clojure.lang.Symbol]
      [:invokeinterface java.util.Map "get" [Object Object]]
      [:checkcast clojure.lang.IFn]
      [:astore (inc args)]
      [:aload (inc args)]
      [:ifnull :fallback]
      [:aload (inc args)]
      ;; load this, always the first argument of IFn
      [:aload 0]]
     ;; load remaining args
     (loads desc slots true)
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
          :long [[:checkcast Long]
                 [:invokevirtual Long "longValue"]]
          "java.lang.Object" nil
          (when (class? ret-type*)
            [[:checkcast ret-type*]])))
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
                  'sci.impl.types.IReified
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
                             [:areturn]]}]
                    (for [{:keys [name desc default]} methods]
                      {:flags #{:public}, :name name
                       :desc desc
                       :emit (emit-method interface name desc default)}
                      ))}))

(set! *warn-on-reflection* true)

(defn type->kw [type]
  (condp = type
    Void/TYPE :void
    Boolean/TYPE :boolean
    Integer/TYPE :int
    Long/TYPE :long
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

(defn gen-classes [_]
  (doseq [i interfaces]
    (insn/write (doto (insn/visit (interface-data i (class->methods i)))
                  insn/define) "target/classes")))
