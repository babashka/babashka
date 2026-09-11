(ns nrepl.util.print
  "Pretty-print related utilities.
  All functions here are simple wrappers compatible with the expectations of
  nrepl.middleware.print/wrap-print."
  {:added "0.8"}
  (:refer-clojure :exclude [pr])
  (:require
   [clojure.pprint :as pp]))

(def ^:private pr-options
  [:print-dup
   :print-readably
   :print-length
   :print-level
   :print-meta
   :print-namespace-maps])

(defn- option->var
  [option]
  (resolve (symbol "clojure.core" (str "*" (name option) "*"))))

(defn- pr-bindings
  [options]
  (->> (select-keys options pr-options)
       (into {} (keep (fn [[option value]]
                        (when-let [var (option->var option)]
                          [var value]))))))

(defn pr
  "Equivalent to `clojure.core/pr`. Any options corresponding to dynamic
  printing configuration vars in `clojure.core` will, if provided, be bound
  accordingly (e.g. `clojure.core/*print-length*` will be used if
  `:print-length` is provided)."
  ([value writer]
   (pr value writer nil))
  ([value writer options]
   (with-bindings (pr-bindings options)
     (if *print-dup*
       (print-dup value writer)
       (print-method value writer)))))

(defn pprint
  "A simple wrapper around `clojure.pprint/write`."
  ([value writer]
   (pprint value writer {}))
  ([value writer options]
   (apply pp/write value (mapcat identity (assoc options :stream writer)))))
