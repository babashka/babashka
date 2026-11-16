(ns cider.nrepl.middleware.undef
  "Middleware for undefining symbols.
  Fully qualified symbols are interpreted as a var to be unmapped in its
  original namespace, whereas unqualified symbols are interpreted as both a var
  and ns alias to be unmapped from the current namespace."
  (:require
   [cider.nrepl.middleware.util.error-handling :refer [with-op-aliases with-safe-transport]]
   [orchard.misc :as misc]))

(defn undef
  "Undefines a symbol.
  When `sym` is unqualified, it is interpreted as both an alias and var to be
  unmapped from the namespace `ns`.
  When qualified (eg. `foo/bar`), it is interpreted as a var to be unmapped in
  the namespace `foo`, which may be an alias in `ns`."
  [{:keys [ns sym symbol]}]
  (let [ns (misc/as-sym ns)
        sym (or sym symbol) ;; for backwards compatibility
        [sym-ns sym-name] ((juxt (comp misc/as-sym namespace) misc/name-sym)
                           (misc/as-sym sym))]
    (if sym-ns
      ;; fully qualified => var in other namespace
      (let [other-ns (get (ns-aliases ns) sym-ns sym-ns)]
        (ns-unmap other-ns sym-name))
      ;; unqualified => alias or var in current ns
      (do (ns-unalias ns sym-name)
          (ns-unmap ns sym-name)))
    sym))

(defn undef-all
  "Undefines all symbol mappings and aliases in the namespace."
  [{:keys [ns]}]
  (let [ns (misc/as-sym ns)]
    ;; Do not remove the default java.lang imports, as they are not relinked on the next load
    ;; see https://github.com/clojure-emacs/cider/issues/3194
    (doseq [[sym ref] (ns-map ns)]
      (when-not (identical? ref #_(get clojure.lang.RT/DEFAULT_IMPORTS sym) ;; BB-PATCH the field is not registered in the image
(get (ns-imports 'clojure.core) sym))
        (ns-unmap ns sym)))
    (doseq [[sym _] (ns-aliases ns)]
      (ns-unalias ns sym))
    ns))

(defn undef-reply
  [msg]
  {:undef (undef msg)})

(defn undef-all-reply
  [msg]
  {:undef-all (undef-all msg)})

(defn handle-undef [handler msg]
  (with-safe-transport handler msg
    (with-op-aliases {"cider/undef" undef-reply
                      "cider/undef-all" undef-all-reply})))
