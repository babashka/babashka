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
    [:areturn]))

(defn loads [desc cast?]
  (let [desc (butlast desc)]
    (vec
     (mapcat (fn [i e]
               (case e
                 :boolean [[:iload i]
                            (when cast? [:invokestatic Boolean "valueOf" [:boolean Boolean]])]
                 :int [[:iload i]
                       (when cast? [:invokestatic Integer "valueOf" [:int Integer]])]
                 [[:aload i]]))
             (range 1 (inc (count desc)))
             desc))))

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
              [:aload (inc args)]
              ;; load this, always the first argument of IFn
              [:aload 0]]
             ;; load remaining args
             (loads desc true)
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
             [[:aload 0]]
             (loads desc false)
             [[:invokespecial class meth desc #_true]
              (return desc)]])))

(defn interface-data [^Class interface methods]
  (let [class-sym (symbol (.getName interface))
        method-names (distinct (map :name methods))]
    {:name (symbol (str "babashka.impl." (.getName interface)))
     :version 1.8
     :interfaces [class-sym
                  'sci.impl.types.IReified
                  'clojure.lang.IMeta
                  'clojure.lang.IObj]
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
                    (for [{:keys [name desc]} methods]
                      {:flags #{:public}, :name name
                       :desc desc
                       :emit (emit-method interface name desc)}))}))

(set! *warn-on-reflection* true)

(defn type->kw [type]
  (condp = type
    Void/TYPE :void
    Boolean/TYPE :boolean
    Integer/TYPE :int
    type))

(defn class->methods [^Class clazz]
  (let [meths (mapv bean (.getMethods clazz))
        meths (mapv (fn [{:keys [name
                                 parameterTypes
                                 returnType]}]
                      (let [ret-type (type->kw returnType)]
                        {:name name
                         :desc (conj (mapv type->kw parameterTypes) ret-type)}))
                    meths)]
    (distinct meths)))

(let [i clojure.lang.IFn]
  (insn/define (insn/visit (interface-data i (class->methods i)))))

(prn :defined)
(babashka.impl.clojure.lang.IFn. nil nil nil)

(defn gen-classes [_]
  (doseq [i interfaces]
    (insn/write (doto (insn/visit (interface-data i (class->methods i)))
                  insn/define) "target/classes")))
