(ns orchard.cljs.meta
  "ClojureScript metadata functions."
  {:author "Gary Trakhman"
   :added "0.5"}
  (:require
   #?(:clj  [orchard.cljs.analysis :as a]
      :cljs [orchard.cljs.analysis :as a :include-macros true])
   #?(:clj  [orchard.misc :as misc]
      :cljs [orchard.misc :as misc :include-macros true])
   [orchard.namespace :as ns]))

(defn normalize-ns-file
  "Helps `normalize-ns-meta` to extract the file from the meta data"
  [meta]
  (or (some-> meta
              :defs
              first
              second
              :file)
      (some-> meta
              :name
              ns/canonical-source
              .getPath)))

(defn normalize-ns-meta
  "Normalize cljs namespace metadata to look like a clj."
  [meta]
  (merge (select-keys meta [:doc :author])
         {:file (normalize-ns-file meta)
          :line 1
          :name (:name meta)
          :ns (:name meta)}))

#_{:clj-kondo/ignore [:unused-binding]}
(defn normalize-macro-ns
  "Normalize cljs namespace macro metadata to look like clj."
  [env var]
  (let [meta (a/ns-meta var)
        ns (:ns meta)]
    (merge (select-keys meta [:doc :ns :name :author])
           {:file #?(:clj (some-> var
                                  ns-interns
                                  first
                                  val
                                  a/var-meta
                                  :file)
                     :cljs (some-> env
                                   (a/ns-interns-from-env (misc/add-ns-macros ns))
                                   first
                                   val
                                   :file))
            :line 1})))

(defn unquote-1
  "Handles some weird double-quoting in the analyzer"
  [[fst & more :as form]]
  (if (= fst 'quote)
    (first more)
    form))

(defn normalize-var-meta
  "Normalize cljs metadata to look like a clj var."
  [meta]
  (update meta :arglists unquote-1))

(defn normalize-macro-meta
  "Normalize cljs macro metadata to look like a clj var."
  [meta]
  (-> meta
      (merge (:meta meta)
             (select-keys meta [:file :ns :name])) ;; :file is more accurate than in :meta
      (update :arglists unquote-1)))

(defn scoped-var-meta
  [env sym & [context-ns]]
  (or (a/find-symbol-meta env sym)
      (let [scope (misc/namespace-sym sym)
            aliased-ns (a/ns-alias env scope context-ns)
            sym (symbol (str (or aliased-ns context-ns) "/" (misc/name-sym sym)))]
        (a/find-symbol-meta env sym))))

(defn macro-namespace
  "Compute the namespace of a macro symbol."
  [env sym & [context-ns]]
  {:pre [(symbol? sym)]}
  (let [ns-from-sym (misc/as-sym (namespace sym))]
    (or (a/macro-ns-alias env ns-from-sym context-ns)
        ns-from-sym
        context-ns)))

(defn scoped-macro-meta
  [env sym & [context-ns]]
  (let [ns (or (macro-namespace env sym context-ns) context-ns)
        sym (symbol (name sym))]
    (when (and ns (find-ns ns))
      (some-> env
              (a/public-macros #?(:clj ns
                                  :cljs (misc/add-ns-macros ns)))
              (get sym)
              a/var-meta))))

(defn referred-macro-meta
  [env sym & [context-ns]]
  (let [ns (macro-namespace env sym context-ns)
        sym (symbol (name sym))]
    (when-let [referred (get (a/referred-macros env ns) sym)]
      #?(:clj (-> referred
                  find-var
                  a/var-meta)
         :cljs (let [referred-ns (symbol (namespace referred))
                     referred-sym (symbol (name referred))]
                 (-> env
                     (a/ns-interns-from-env (misc/add-ns-macros referred-ns))
                     (get referred-sym)
                     a/var-meta))))))

(defn aliased-macro-var
  [env sym & [context-ns]]
  (let [ns (macro-namespace env sym context-ns)]
    (some-> env
            (a/macro-ns-alias sym ns)
            #?(:cljs misc/add-ns-macros)
            find-ns)))

(defn special-sym-meta
  [env sym]
  (some-> (a/special-meta env sym) normalize-var-meta))
