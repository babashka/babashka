(ns orchard.apropos
  "Search symbols and docs matching a regular expression"
  {:author "Jeff Valk"}
  (:require
   [orchard.meta :refer [var-name var-doc] :as m]
   [orchard.misc :as misc]
   [orchard.query :as query])
  (:import
   (clojure.lang MultiFn Var)))

;;; ## Overview
;;
;; This namespace provides regular expression search across namespaces for
;; both symbols and documentation. Results ordered for the most common usages:
;; symbols from the current namespace are preferred, then `clojure.*` symbols,
;; and then other results.

;;; ## Symbol Search

(defn- priority-ns? [ns]
  (some-> ns ns-name name (.startsWith "clojure.")))

(defn- default-comparator [this-ns]
  (fn [^Var x, ^Var y]
    (cond
      (= x y)  0
      (nil? x) 1
      (nil? y) -1
      :else
      (let [ns1 (when (instance? Var x) (.ns ^Var x))
            ns2 (when (instance? Var y) (.ns ^Var y))
            ;; First, vars from the namespace `this-ns`.
            ;; Then, vars from "priority" namespaces (everything from clojure.*)
            ;; Finally, all the rest.
            prio-ns1 (cond (and this-ns (= ns1 this-ns)) 0
                           (priority-ns? ns1) 1
                           :else 2)
            prio-ns2 (cond (and this-ns (= ns2 this-ns)) 0
                           (priority-ns? ns2) 1
                           :else 2)
            c (compare prio-ns1 prio-ns2)]
        (if (zero? c)
          (compare (str x) (str y))
          c)))))

(defn apropos-sort
  "Return a list of vars, ordered with `ns` first,
  followed by `clojure.*` namespaces, and then all others sorted
  alphabetically."
  [ns vars]
  (assert (every? (some-fn class? var? symbol?) vars)
          (pr-str vars))
  ;; https://clojure.org/guides/comparators
  (sort (default-comparator ns) vars))

(defn find-symbols
  "Takes a map and returns a list of maps containing name, doc and type.
  `:var-query` See `#'query/vars`.
  `:full-doc?` Causes the full doc to be returned instead of the abbreviated form.
  `:ns` If provided, the results will be sorted to show this namespace first."
  [{:keys [ns full-doc? var-query]}]
  (let [var-doc*    (if full-doc? var-doc (partial var-doc 1))]
    (->> (query/vars
          (assoc var-query
                 :manipulate-vars
                 (fn [nss vars]
                   (if (first (filter #(= (find-ns 'clojure.core) %) nss))
                     (concat (keys (or (misc/require-and-resolve 'clojure.repl/special-doc-map)
                                       (misc/require-and-resolve 'cljs.repl/special-doc-map)))
                             '[& catch finally]
                             vars)
                     vars))))
         (apropos-sort ns)
         (map (fn [v]
                {:name (var-name v)
                 :doc  (var-doc* v)
                 :type (cond (special-symbol? v) :special-form
                             (:macro (meta v)) :macro
                             (or (fn? @v) (instance? MultiFn @v)) :function
                             :else :variable)})))))
