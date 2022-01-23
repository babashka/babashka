(ns orchestra.make-fns
  (:require #?@(:clj [[clojure.spec.alpha :as s]]
                :cljs [[cljs.spec.alpha :as s]])))

#?(:clj (defmacro make-fns [fn-count]
          (let [cljs? (-> &env :ns some?)]
            `(do
               ~@(for [i (range fn-count)]
                   (let [fn-name (symbol (str "fn-" i))]
                     `(do
                        (defn ~fn-name []
                          (str ~fn-name))
                        (~(if cljs?
                            'cljs.spec.alpha/fdef
                            's/fdef) ~fn-name))))))))
