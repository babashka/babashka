(ns babashka.impl.reify2
  (:require [babashka.impl.reify2.interfaces :refer [interfaces]]))

(set! *warn-on-reflection* false)

(defn method-or-bust [methods k]
  (or (get methods k)
      (throw (UnsupportedOperationException. "Method not implemented: " k))))

(defn reify-ifn [m]
  (let [methods (:methods m)
        invoke-fn (or (get methods 'invoke)
                      (fn [& _args]
                        (throw (UnsupportedOperationException. "Method not implemented: invoke"))))
        apply-fn (or (get methods 'applyTo)
                     (fn [& _args]
                       (throw (UnsupportedOperationException. "Method not implemented: applyTo"))))]
    (reify
      sci.impl.types.IReified
      (getMethods [_] (:methods m))
      (getInterfaces [_] (:interfaces m))
      (getProtocols [_] (:protocols m))
      clojure.lang.IFn
      (invoke [this] (invoke-fn this))
      (invoke [this a0] (invoke-fn this a0))
      (invoke [this a0 a1] (invoke-fn this a0 a1))
      (invoke [this a0 a1 a2] (invoke-fn this a0 a1 a2))
      (invoke [this a0 a1 a2 a3] (invoke-fn this a0 a1 a2 a3))
      (invoke [this a0 a1 a2 a3 a4] (invoke-fn this a0 a1 a2 a3 a4))
      (invoke [this a0 a1 a2 a3 a4 a5] (invoke-fn this a0 a1 a2 a3 a4 a5))
      (invoke [this a0 a1 a2 a3 a4 a5 a6] (invoke-fn this a0 a1 a2 a3 a4 a5 a6))
      (invoke [this a0 a1 a2 a3 a4 a5 a6 a7] (invoke-fn this a0 a1 a2 a3 a4 a5 a6 a7))
      (invoke [this a0 a1 a2 a3 a4 a5 a6 a7 a8] (invoke-fn this a0 a1 a2 a3 a4 a5 a6 a7 a8))
      (invoke [this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9] (invoke-fn this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9))
      (invoke [this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10] (invoke-fn this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10))
      (invoke [this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11] (invoke-fn this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11))
      (invoke [this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12] (invoke-fn this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12))
      (invoke [this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13] (invoke-fn this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13))
      (invoke [this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14] (invoke-fn this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14))
      (invoke [this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15] (invoke-fn this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15))
      (invoke [this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16] (invoke-fn this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16))
      (invoke [this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17] (invoke-fn this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17))
      (invoke [this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18] (invoke-fn this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18))
      (invoke [this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19] (invoke-fn this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19))
      (invoke [this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20] (invoke-fn this a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20))
      (applyTo [this arglist] (apply-fn this arglist)))))

(defn reify-object [m]
  (let [methods (:methods m)
        toString-fn (or (get methods 'toString)
                        (fn [this]
                          (str
                           (.getName (.getClass this))
                           "@"
                           (Integer/toHexString (.hashCode this)))))
        equals-fn (or (get methods 'equals)
                      #_{:clj-kondo/ignore [:redundant-fn-wrapper]}
                      (fn [this other]
                        (identical? this other)))
        hashCode-fn (or (get methods 'hashCode)
                        (fn [this]
                          (System/identityHashCode this)))]
    (reify
      sci.impl.types.IReified
      (getMethods [_] (:methods m))
      (getInterfaces [_] (:interfaces m))
      (getProtocols [_] (:protocols m))
      java.lang.Object
      (toString [this] (toString-fn this))
      (equals [this other] (equals-fn this other))
      (hashCode [this] (hashCode-fn this)))))

(defmacro gen-reify-fn []
  `(fn [~'m]
     (when (> (count (:interfaces ~'m)) 1)
       (throw (UnsupportedOperationException. "babashka reify only supports implementing a single interface")))
     (if (empty? (:interfaces ~'m))
       (reify
         sci.impl.types.IReified
         (getMethods [_] (:methods ~'m))
         (getInterfaces [_] (:interfaces ~'m))
         (getProtocols [_] (:protocols ~'m)))
       (case (.getName ~(with-meta `(first (:interfaces ~'m))
                          {:tag 'Class}))
         ~@(mapcat identity
                   (cons
                    ["clojure.lang.IFn"
                     `(reify-ifn ~'m)
                     "java.lang.Object"
                     `(reify-object ~'m)]
                    (for [i interfaces]
                      (let [in (.getName ^Class i)]
                        [in
                         `(new ~(symbol (str "babashka.impl." in))
                               (:methods ~'m)
                               (:interfaces ~'m)
                               (:protocols ~'m))]))))))))

(def reify-fn (gen-reify-fn))
