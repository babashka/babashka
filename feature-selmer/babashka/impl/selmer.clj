(ns babashka.impl.selmer
  {:no-doc true}
  (:require [babashka.impl.classpath :refer [resource]]
            [babashka.impl.common :refer [ctx]]
            [sci.core :as sci]
            [selmer.filters :as filters]
            [selmer.parser]
            [selmer.tags :as tags]
            [selmer.util :as util]
            [selmer.validator :as validator]))

(def spns (sci/create-ns 'selmer.parser nil))

(def include #{'env-map})

(defn make-ns [ns sci-ns]
  (reduce (fn [ns-map [var-name var]]
            (let [m (meta var)
                  no-doc (:no-doc m)
                  doc (:doc m)
                  arglists (:arglists m)]
              (if (and no-doc (not (contains? include var-name)))
                ns-map
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

(def suns (sci/create-ns 'selmer.util nil))

(def escape-variables
  (sci/new-dynamic-var '*escape-variables* util/*escape-variables* {:ns suns}))

(defn render-file
  "Parses files if there isn't a memoized post-parse vector ready to go,
  renders post-parse vector with passed context-map regardless. Double-checks
  last-modified on files. Uses classpath for filename-or-url path "
  [& args]
  (binding [util/*resource-fn* resource
            util/*escape-variables* @escape-variables]
    (apply selmer.parser/render-file args)))

(defn render
  " render takes the string, the context-map and possibly also opts. "
  [& args]
  (binding [util/*escape-variables* @escape-variables]
    (apply selmer.parser/render args)))

(defn render-template
  " vector of ^selmer.node.INodes and a context map."
  [template context-map]
  (binding [util/*escape-variables* @escape-variables]
    (selmer.parser/render-template template context-map)))

(defn sci-resolve [fqs]
  (let [res (sci/eval-form @ctx (list 'clojure.core/resolve (list 'quote fqs)))]
    (if (instance? clojure.lang.IDeref res) @res res)))

(defn resolve-var-from-kw [env kw]
  (when-let [value (if (namespace kw)
                     (try (sci-resolve (symbol (str (namespace kw) "/" (name kw))))
                          (catch java.lang.RuntimeException _ nil))
                     (or
                      ;; check local env first
                      (get env kw nil)
                      (try (sci-resolve (symbol (str (name kw))))
                           (catch java.lang.RuntimeException _ nil))))]
    {kw value}))

(def selmer-parser-namespace
  (-> selmer-parser-ns
      (assoc 'render-file (sci/copy-var render-file spns)
             'render      (sci/copy-var render spns)
             'render-template (sci/copy-var render-template spns)
             'resolve-var-from-kw (sci/copy-var resolve-var-from-kw spns))))

(def stns (sci/create-ns 'selmer.tags nil))

(def selmer-tags-ns (sci/create-ns 'selmer.tags stns))

(def selmer-tags-namespace
  {;; needed by selmer.parser/add-tag!
   'expr-tags (sci/copy-var tags/expr-tags stns)
   ;; needed by selmer.parser/add-tag!
   'tag-handler (sci/copy-var tags/tag-handler stns)})

(def sfns (sci/create-ns 'selmer.filters nil))

(def selmer-filters-namespace
  {'add-filter! (sci/copy-var filters/add-filter! sfns)
   'remove-filter! (sci/copy-var filters/remove-filter! sfns)})

(defn turn-off-escaping! []
  (sci/alter-var-root escape-variables (constantly false)))

(defn turn-on-escaping! []
  (sci/alter-var-root escape-variables (constantly true)))

(defmacro with-escaping [& body]
  `(binding [selmer.util/*escape-variables* true]
     ~@body))

(defmacro without-escaping [& body]
  `(binding [selmer.util/*escape-variables* false]
     ~@body))

(def selmer-util-namespace
  {'turn-off-escaping! (sci/copy-var turn-off-escaping! suns)
   'turn-on-escaping! (sci/copy-var turn-on-escaping! suns)
   '*escape-variables* escape-variables
   'with-escaping (sci/copy-var with-escaping suns)
   'without-escaping (sci/copy-var without-escaping suns)
   'set-missing-value-formatter! (sci/copy-var util/set-missing-value-formatter! suns)})

(def svns (sci/create-ns 'selmer.validator nil))

(def selmer-validator-namespace
  {'validate-off! (sci/copy-var validator/validate-off! svns)})
