(ns babashka.impl.rewrite-clj
  {:no-doc true}
  (:require [rewrite-clj.node]
            [rewrite-clj.parser]
            [rewrite-clj.zip]
            [sci.core :as sci]))

(def nns (sci/create-ns 'rewrite-clj.node nil))
(def pens (sci/create-ns 'rewrite-clj.paredit nil))
(def pns (sci/create-ns 'rewrite-clj.parser nil))
(def zns (sci/create-ns 'rewrite-clj.zip nil))

#_(defmacro copy-var
  "Copies contents from var `sym` to a new sci var. The value `ns` is an
  object created with `sci.core/create-ns`."
  ([sym ns]
   `(let [ns# ~ns
          var# (var ~sym)
          val# (deref var#)
          m# (-> var# meta)
          ns-name# (vars/getName ns#)
          name# (:name m#)
          name-sym# (symbol (str ns-name#) (str name#))
          new-m# {:doc (:doc m#)
                  :name name#
                  :arglists (:arglists m#)
                  :ns ns#}]
      (cond (:dynamic m#)
            (new-dynamic-var name# val# new-m#)
            (:macro m#)
            (new-macro-var name# val# new-m#)
            :else (new-var name# val# new-m#)))))

(defn make-ns [ns sci-ns]
  (reduce (fn [ns-map [var-name var]]
            (let [m (meta var)
                  no-doc (:no-doc m)]
              (if no-doc ns-map
                  (assoc ns-map var-name
                         (sci/new-var (symbol var-name) @var
                                      {:ns sci-ns})))))
          {}
          (ns-publics ns)))

(def node-namespace
  (make-ns 'rewrite-clj.node nns))

(def parser-namespace
  (make-ns 'rewrite-clj.parser pns))

(def paredit-namespace
  (make-ns 'rewrite-clj.paredit pens))

(def zip-namespace
  (make-ns 'rewrite-clj.zip zns))
