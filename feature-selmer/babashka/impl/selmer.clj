(ns babashka.impl.selmer
  {:no-doc true}
  (:require [babashka.impl.classpath :refer [resource]]
            [sci.core :as sci]
            [selmer.parser]
            [selmer.util :refer [*resource-fn*]]))

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

(def selmer-parser-ns (make-ns 'selmer.parser sns))

(defn render-file
  "Parses files if there isn't a memoized post-parse vector ready to go,
  renders post-parse vector with passed context-map regardless. Double-checks
  last-modified on files. Uses classpath for filename-or-url path "
  [& args]
  (binding [*resource-fn* resource]
    (apply selmer.parser/render-file args)))

(def selmer-namespace
  (assoc selmer-parser-ns 'render-file (sci/copy-var render-file sns)))
