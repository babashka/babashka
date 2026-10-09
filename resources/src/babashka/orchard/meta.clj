(ns orchard.meta
  "Stand-in for orchard.meta, for the debugger."
  (:require [clojure.java.io :as io]
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
