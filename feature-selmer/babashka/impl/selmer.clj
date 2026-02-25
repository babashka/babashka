(ns babashka.impl.selmer
  {:no-doc true}
  (:require [babashka.impl.classpath :refer [resource]]
            [babashka.impl.common :refer [ctx]]
            [sci.core :as sci]
            [selmer.filters :as filters]
            [selmer.filter-parser :as fp]
            [selmer.parser]
            [selmer.tags :as tags]
            [selmer.util :as util]
            [selmer.validator :as validator]))

(def spns (sci/create-ns 'selmer.parser nil))

(def include #{'env-map})

(defn make-ns [ns sci-ns]
  (reduce (fn [ns-map [var-name var]]
            (let [no-doc (:no-doc (meta var))]
              (if (and no-doc (not (contains? include var-name)))
                ns-map
                (assoc ns-map var-name (sci/copy-var* var sci-ns)))))
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

(defn sci-ns-resolve [ns fqs]
  (sci/eval-form (ctx) (list 'clojure.core/ns-resolve ns (list 'quote fqs))))

(defn force! [x]
  (if (instance? clojure.lang.IDeref x) @x x))

(defn ^:no-doc resolve-var-from-kw [ns env kw]
  (if (namespace kw)
    (when-let [v (sci-ns-resolve ns (symbol (str (namespace kw) "/" (name kw))))] {kw (force! v)})
    (or
     ;; check local env first
     (when-let [[_ v] (find env kw)] {kw v})
     (when-let [v (sci-ns-resolve ns (symbol (name kw)))] {kw (force! v)}))))

(defmacro <<
  "Resolves the variables from your template string from the local-env, or the
  namespace and puts them into your template for you.
  e.g. (let [a 1] (<< \"{{a}} + {{a}} = 2\")) ;;=> \"1 + 1 = 2\" "
  [s]
  `(->> (selmer.parser/known-variables ~s)
        (mapv #(selmer.parser/resolve-var-from-kw ~(deref sci/ns) (selmer.parser/env-map) %))
        (apply merge)
        (selmer.parser/render ~s)))

(defn resolve-arg
  "Resolves an arg as passed to an add-tag! handler using the provided
  context-map.

  A custom tag handler will receive a seq of args as its first argument.
  With this function, you can selectively resolve one or more of those args
  so that if they contain literals, the literal value is returned, and if they
  contain templates of any sort, which can itself have variables, filters or
  tags in it, they will be returned resolved, applied and rendered.

  Example:
    (resolve-arg {{header-name|upper}} {:header-name \"My Page\"})
    => \"MY PAGE\""
  [arg context-map]
  (if (fp/literal? arg)
    (fp/parse-literal arg)
    (render arg context-map)))

(def selmer-parser-namespace
  (-> selmer-parser-ns
      (assoc 'render-file (sci/copy-var render-file spns {:copy-meta-from selmer.parser/render-file})
             'render      (sci/copy-var render spns {:copy-meta-from selmer.parser/render})
             'render-template (sci/copy-var render-template spns {:copy-meta-from selmer.parser/render-template})
             'resolve-var-from-kw (sci/copy-var resolve-var-from-kw spns {:copy-meta-from selmer.parser/resolve-var-from-kw})
             'resolve-arg (sci/copy-var resolve-arg spns {:copy-meta-from selmer.parser/resolve-arg})
             '<< (sci/copy-var << spns {:copy-meta-from selmer.parser/<<}))))

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
   'remove-filter! (sci/copy-var filters/remove-filter! sfns)
   'get-filter (sci/copy-var filters/get-filter sfns)
   'filters (sci/copy-var filters/filters sfns)})

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
  {'turn-off-escaping! (sci/copy-var turn-off-escaping! suns {:copy-meta-from selmer.util/turn-off-escaping!})
   'turn-on-escaping! (sci/copy-var turn-on-escaping! suns {:copy-meta-from selmer.util/turn-on-escaping!})
   '*escape-variables* escape-variables
   'with-escaping (sci/copy-var with-escaping suns {:copy-meta-from selmer.util/with-escaping})
   'without-escaping (sci/copy-var without-escaping suns {:copy-meta-from selmer.util/without-escaping})
   'set-missing-value-formatter! (sci/copy-var util/set-missing-value-formatter! suns)})

(def svns (sci/create-ns 'selmer.validator nil))

(def selmer-validator-namespace
  {'validate-off! (sci/copy-var validator/validate-off! svns)})
