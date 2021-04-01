(ns babashka.impl.rewrite-clj
  {:no-doc true}
  (:require [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [sci.core :as sci :refer [copy-var]]))

(def nns (sci/create-ns 'rewrite-clj.node nil))
(def pns (sci/create-ns 'rewrite-clj.parser nil))

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
            (assoc ns-map var-name
                   (sci/new-var (symbol var-name) @var
                                {:ns sci-ns})))
          {}
          (ns-publics ns)))

(def node-namespace
  (make-ns 'rewrite-clj.node nns))

(def parser-namespace
  {'parse (copy-var p/parse pns)
   'parse-file (copy-var p/parse-file pns)
   'parse-all (copy-var p/parse-all pns)
   'parse-string (copy-var p/parse-string pns)
   'parse-string-all (copy-var p/parse-string-all pns)})
