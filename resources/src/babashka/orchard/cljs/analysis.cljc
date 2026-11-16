(ns orchard.cljs.analysis
  "ClojureScript analysis functions."
  {:author "Gary Trakhman"
   :added "0.5"}
  (:refer-clojure :exclude [all-ns find-ns find-var ns-aliases ns-resolve])
  (:require
   [clojure.string :as str]
   [orchard.misc :as misc]))

(defn all-ns [{namespaces :cljs.analyzer/namespaces}]
  (into {}
        (remove (fn [[ns-sym ns]]
                  ;; Remove pseudo-namespaces that the cljs analyzer
                  ;; started returning at some point:
                  (or (some-> ns-sym name (.startsWith "goog."))
                      ;; recent CLJS versions include data about macro namespaces in the
                      ;; compiler env, but we should not include them in completions or pass
                      ;; them to format-ns unless they're actually required (which is handled
                      ;; by macro-ns-candidates below):
                      (and (contains? ns :macros)
                           (= 1 (count ns))))))
        namespaces))

(defn find-ns
  [env ns]
  (get (all-ns env) ns))

;; Code adapted from clojure-complete (http://github.com/ninjudd/clojure-complete)

(defn imports
  "Returns a map of [import-name] to [ns-qualified-import-name] for all imports
  in the given namespace."
  [env ns]
  (:imports (find-ns env ns)))

(defn ns-aliases
  "Returns a map of [ns-name-or-alias] to [ns-name] for the given namespace."
  [env ns]
  (let [imports (imports env ns)]
    (->> (find-ns env ns)
         :requires
         (filter #(not (contains? imports (key %))))
         (into {}))))

(defn macro-ns-aliases
  "Returns a map of [macro-ns-name-or-alias] to [macro-ns-name] for the given namespace."
  [env ns]
  (:require-macros (find-ns env ns)))

(defn- expand-refer-map
  [m]
  (into {} (for [[k v] m] [k (symbol (str v "/" k))])))

(defn referred-vars
  "Returns a map of [var-name] to [ns-qualified-var-name] for all referred vars
  in the given namespace."
  [env ns]
  (->> (find-ns env ns)
       :uses
       expand-refer-map))

(defn referred-macros
  "Returns a map of [macro-name] to [ns-qualified-macro-name] for all referred
  macros in the given namespace."
  [env ns]
  (->> (find-ns env ns)
       :use-macros
       expand-refer-map))

(defn ns-alias
  "If sym is an alias to, or the name of, a namespace referred to in ns, returns
  the name of the namespace; else returns nil."
  [env sym ns]
  (get (ns-aliases env ns) sym))

(defn macro-ns-alias
  "If sym is an alias to, or the name of, a macro namespace referred to in ns,
  returns the name of the macro namespace; else returns nil."
  [env sym ns]
  (get (macro-ns-aliases env ns) sym))

(defn- public?
  [[_ var]]
  (not (:private var)))

(defn- named?
  [[_ var]]
  (not (:anonymous var)))

(defn- foreign-protocol?
  [[_ var]]
  (and (:impls var)
       (not (:protocol-symbol var))))

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- macro?
  [[_ var]]
  (:macro (meta var)))

(defn ns-vars
  "Returns a list of the vars declared in the ns."
  [env ns]
  (->> (find-ns env ns)
       :defs
       (filter (every-pred named? (complement foreign-protocol?)))
       (into {})))

(defn public-vars
  "Returns a list of the public vars declared in the ns."
  [env ns]
  (->> (find-ns env ns)
       :defs
       (filter (every-pred named? public? (complement foreign-protocol?)))
       (into {})))

#_{:clj-kondo/ignore [:unused-binding]}
(defn public-macros
  "Given a namespace return all the public var analysis maps. Analogous to
  clojure.core/ns-publics but returns var analysis maps not vars.

  Inspired by the ns-publics in cljs.analyzer.api."
  [env ns]
  {:pre [(symbol? ns)]}
  #?(:clj (when (and ns (clojure.core/find-ns ns))
            (->> (ns-publics ns)
                 (filter macro?)
                 (into {})))
     :cljs (->> (merge
                 (get-in env [:cljs.analyzer/namespaces ns :macros])
                 (get-in env [:cljs.analyzer/namespaces ns :defs]))
                (remove (fn [[k v]] (:private v)))
                (into {}))))

(defn core-vars
  "Returns a list of cljs.core vars visible to the ns."
  [env ns]
  (let [vars (public-vars env 'cljs.core)
        excludes (:excludes (find-ns env ns))]
    (apply dissoc vars excludes)))

(defn core-macros
  "Returns a list of cljs.core macros visible to the ns."
  [env ns]
  (let [macros (public-macros env #?(:clj 'cljs.core :cljs 'cljs.core$macros))
        excludes (:excludes (find-ns env ns))]
    (apply dissoc macros excludes)))

(defn ns-interns-from-env
  "Given a namespace return all the var analysis maps. Analogous to
  clojure.core/ns-interns but returns var analysis maps not vars.

  Directly from cljs.analyzer.api."
  [env ns]
  {:pre [(symbol? ns)]}
  (merge
   (get-in env [:cljs.analyzer/namespaces ns :macros])
   (get-in env [:cljs.analyzer/namespaces ns :defs])))

(defn sanitize-ns
  "Add :ns from :name if missing."
  [m]
  (-> m
      (assoc :ns (or (:ns m) (:name m)))
      (update :ns misc/namespace-sym)
      (update :name misc/name-sym)))

(defn var-meta
  "Return meta for the var, we wrap it in order to support both JVM and
  self-host."
  [var]
  (cond-> {}
    (map? var) (merge var)
    (var? var) (-> (merge (meta var))
                   (update :ns #(cond-> % (misc/ns-obj? %) ns-name)))
    true sanitize-ns
    #?@(:cljs [true (-> (update :ns misc/remove-macros)
                        (update :name misc/remove-macros))])))

(defn ns-meta
  "Return meta for the var, we wrap it in order to support both JVM and
  self-host."
  [var]
  (cond-> {}
    (map? var) (merge var)
    (misc/ns-obj? var) (merge {:ns (ns-name var)
                               :name (ns-name var)})
    true sanitize-ns
    #?@(:cljs [true (-> (update :ns misc/remove-macros)
                        (update :name misc/remove-macros))])))

(defn find-symbol-meta
  "Given a `sym`, gets the analyzer metadata for that var."
  ([env sym]
   (find-symbol-meta env
                     (misc/namespace-sym sym)
                     (misc/name-sym sym)))

  ([env ns sym]
   (let [ns (find-ns env ns)]
     (some-> ns
             :defs
             (get sym)
             var-meta))))

(defn ns-resolve
  "Obtains info for a `sym` that may refer to:

  * a var within the same ns;
  * a var referred from another ns; or
  * a var that is alias-qualified e.g. `foo/bar`."
  [env ns sym]
  (let [[referred-var-ns referred-var-symbol :as referred]
        (some-> env (referred-vars ns) (get sym) (str) (str/split #"/") (->> (map symbol)))]
    (if referred
      (find-symbol-meta env referred-var-ns referred-var-symbol)
      (let [sym-ns (some-> sym namespace symbol)
            ns (if-not sym-ns
                 ns
                 (let [aliases (ns-aliases env ns)]
                   (get aliases sym-ns sym-ns)))]
        (find-symbol-meta env ns (misc/name-sym sym))))))

#_{:clj-kondo/ignore [:unresolved-namespace]}
(defn special-meta
  "Given a special symbol, gets the analyzer metadata."
  [_ sym]
  (when-let [meta #?(:clj (or (get (misc/require-and-resolve 'cljs.repl/special-doc-map) sym)
                              (get (misc/require-and-resolve 'cljs.repl/repl-special-doc-map) sym))
                     :cljs (or (get special/special-doc-map sym)
                               (get special/repl-special-doc-map sym)))]
    (merge {:name sym
            :ns 'cljs.core
            :special-form true}
           meta)))
