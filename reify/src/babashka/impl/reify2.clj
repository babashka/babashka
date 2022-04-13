(ns babashka.impl.reify2
  (:require [babashka.impl.reify2.interfaces :refer [interfaces]]))

(set! *warn-on-reflection* false)

(defn method-or-bust [methods k]
  (or (get methods k)
      (throw (UnsupportedOperationException. "Method not implemented: " k))))

(defmacro gen-reify-fn []
  `(fn [~'m]
     (if (empty? (:interfaces ~'m))
       (new babashka.impl.clojure.lang.Indexed
            (:methods ~'m)
            (:interfaces ~'m)
            (:protocols ~'m))
       (case (.getName ~(with-meta `(first (:interfaces ~'m))
                          {:tag 'Class}))
         "java.lang.Object"
         (reify java.lang.Object
           (toString [~'this]
             ((method-or-bust (:methods ~'m) (quote ~'toString)) ~'this)))
         ~@(mapcat identity
                   (for [i interfaces]
                     (let [in (.getName ^Class i)]
                       [in
                        `(new ~(symbol (str "babashka.impl." in))
                              (:methods ~'m)
                              (:interfaces ~'m)
                              (:protocols ~'m))])))))))

(def reify-fn (gen-reify-fn))
