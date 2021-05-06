(ns babashka.impl.selmer
  {:no-doc true}
  (:require [babashka.impl.classpath :refer [resource]]
            [sci.core :as sci]
            [selmer.parser]
            [selmer.tags :as tags]
            [selmer.util :refer [*resource-fn*]]))

(def spns (sci/create-ns 'selmer.parser nil))

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

(def selmer-parser-ns (make-ns 'selmer.parser spns))

(defn render-file
  "Parses files if there isn't a memoized post-parse vector ready to go,
  renders post-parse vector with passed context-map regardless. Double-checks
  last-modified on files. Uses classpath for filename-or-url path "
  [& args]
  (binding [*resource-fn* resource]
    (apply selmer.parser/render-file args)))

(def selmer-parser-namespace
  (assoc selmer-parser-ns 'render-file (sci/copy-var render-file spns)))

(def stns (sci/create-ns 'selmer.tags nil))

(def selmer-tags-ns (sci/create-ns 'selmer.tags stns))

(def selmer-tags-namespace
  {;; needed by selmer.parser/add-tag! 
   'expr-tags (sci/copy-var tags/expr-tags stns)
   ;; needed by selmer.parser/add-tag!
   'tag-handler (sci/copy-var tags/tag-handler stns)})
