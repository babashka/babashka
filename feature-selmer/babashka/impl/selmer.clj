(ns babashka.impl.selmer
  {:no-doc true}
  (:require [sci.core :as sci]
            [selmer.parser]))

(def sns (sci/create-ns 'selmer.parser nil))

(defn make-ns [ns sci-ns]
  (reduce (fn [ns-map [var-name var]]
            (let [m (meta var)
                  no-doc (:no-doc m)
                  doc (:doc m)
                  arglists (:arglists m)]
              (if no-doc ns-map
                  (assoc ns-map var-name
                         (sci/new-var (symbol var-name) @var
                                      (cond-> {:ns sci-ns
                                               :name (:name m)}
                                        (:macro m) (assoc :macro true)
                                        doc (assoc :doc doc)
                                        arglists (assoc :arglists arglists)))))))
          {}
          (ns-publics ns)))

(def selmer-namespace
  (make-ns 'selmer.parser sns))
