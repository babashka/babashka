(ns orchard.meta
  "Stand-in for orchard.meta, used by the debugger and orchard.query."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(defn merge-meta
  "Non-throwing version of (vary-meta obj merge metamap-1 metamap-2 ...).
  Like `vary-meta`, this only applies to immutable objects. For
  instance, this function does nothing on atoms, because the metadata
  of an `atom` is part of the atom itself and can only be changed
  destructively."
  {:style/indent 1}
  [obj & metamaps]
  (try
    (apply vary-meta obj merge metamaps)
    (catch Exception _e obj)))

(defn strip-meta
  "Strip meta from form.
  If keys are provided, strip only those keys."
  ([form] (strip-meta form nil))
  ([form keys]
   (if (and (instance? clojure.lang.IObj form)
            (meta form))
     (with-meta form (when keys (apply dissoc (meta form) keys)))
     form)))

(defn macroexpand-all
  "Like `clojure.walk/macroexpand-all`, but preserves and macroexpands
  metadata. Also store the original form (unexpanded and stripped of
  metadata) in the metadata of the expanded form under original-key."
  [form & [original-key]]
  (let [md (meta form)
        expanded (walk/walk #(macroexpand-all % original-key)
                            identity
                            (if (seq? form)
                              ;; Without this, `macroexpand-all`
                              ;; throws if called on `defrecords`.
                              (try (macroexpand form)
                                   (catch ClassNotFoundException _e form))
                              form))]
    (if md
      ;; Macroexpand the metadata too, because sometimes metadata
      ;; contains, for example, functions. This is the case for
      ;; deftest forms.
      (merge-meta expanded
        (macroexpand-all md)
        (when original-key
          ;; We have to quote this, or it will get evaluated by
          ;; Clojure (even though it's inside meta).
          {original-key (list 'quote (strip-meta form))}))
      expanded)))

(defn var-code
  "Returns the metadata of var v with its source form under :form and the
  source text under :code, or nil if the source cannot be found."
  [v]
  (let [{:keys [file line column] :as m} (meta v)]
    (when (and file line)
      (when-let [res (or (io/resource file)
                         (let [f (io/file file)] (when (.exists f) f)))]
        (with-open [rdr (clojure.lang.LineNumberingPushbackReader. (io/reader res))]
          (dotimes [_ (dec line)] (.readLine rdr))
          (dotimes [_ (dec (or column 1))] (.read rdr))
          (let [[form code] (binding [*ns* (:ns m)]
                              (read+string {:read-cond :allow} rdr))]
            (assoc (select-keys m [:file :line :column :name])
                   :ns (ns-name (:ns m))
                   :form form
                   :code code)))))))

(defn meta+
  "Returns the metadata of var v, or a name for a special form symbol, which
  orchard.apropos adds to the vars it searches."
  [v]
  (if (and (symbol? v) (special-symbol? v))
    {:name v :special-form true}
    (meta v)))

(defn var-name
  "Return special form or var's namespace-qualified name as string."
  [v]
  (let [mta (meta+ v)]
    (if-let [ns (:ns mta)]
      (str (ns-name ns) "/" (:name mta))
      (name (:name mta)))))

(defn var-doc
  "Return special form or var's docstring, optionally limiting the number of
  sentences returned to n."
  ([v]
   (or (:doc (meta+ v))
       "(not documented)"))
  ([n v]
   (->> (-> (var-doc v)
            (str/replace #"\s+" " ") ; normalize whitespace
            (str/split #"(?<=\.) ")) ; split sentences
        (take n)
        (str/join " "))))

(defn resolve-var
  "Returns the var sym resolves to in the namespace named ns, or nil if none."
  [ns sym]
  {:pre [(symbol? ns) (symbol? sym)]}
  (when-let [ns (find-ns ns)]
    (try (ns-resolve ns sym)
         (catch Exception _ nil))))

(defn classify-symbol
  "Returns :macro, :inline, :special or :function for sym resolved in the
  namespace named ns, or nil if sym resolves to no var."
  [ns sym]
  (if (special-symbol? sym)
    :special
    (when-let [v (resolve-var ns sym)]
      (when (var? v)
        (let [m (meta v)]
          (cond
            (:macro m) :macro
            (:inline m) :inline
            :else :function))))))
