(ns cider.nrepl.pprint
  "Pretty-print related utilities.
  All functions here are simple wrappers compatible with the expectations of
  nrepl.middleware.print/wrap-print."
  {:added "0.20"}
  (:refer-clojure :exclude [pr])
  (:require
   [clojure.pprint]
   [orchard.pp :as pp]
   [orchard.misc :as misc]))

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

(defn- try-resolve [var-symbol pprinter-name]
  (or (misc/require-and-resolve var-symbol)
      (binding [*out* *err*]
        (println (format "Could not load %s namespace. To use %s pretty-printing with CIDER, add it to dependencies explicitly."
                         (namespace var-symbol) pprinter-name))
        nil)))

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

(defn- orchard-pp
  "Pretty-print `value` to `writer` with `orchard.pp`, translating the common
  print options it doesn't read natively: `:right-margin` becomes its
  `:max-width`, and `:length`/`:level` are bound to `*print-length*` /
  `*print-level*` (the vars orchard.pp consults)."
  [value writer {:keys [length level right-margin] :as options}]
  (binding [*print-length* (if (contains? options :length) length *print-length*)
            *print-level*  (if (contains? options :level) level *print-level*)]
    (pp/pprint writer value (cond-> options
                              right-margin (assoc :max-width right-margin)))))

(defn pprint
  "Pretty-print `value` using `orchard.pp`. Unlike `clojure.pprint`, it does not
  realize lazy sequences while printing, so it won't interleave their side
  effects into - and corrupt - the printed result (see #903). Honors the usual
  `:length`, `:level` and `:right-margin` print options."
  ([value writer]
   (pprint value writer {}))
  ([value writer options]
   (orchard-pp value writer options)))

(defn clojure-pprint
  "Pretty-print `value` with `clojure.pprint/write`. Prefer `pprint` (backed by
  `orchard.pp`); this is kept for callers that specifically want
  `clojure.pprint`'s output, but note that it realizes lazy seqs as it prints."
  ([value writer]
   (clojure-pprint value writer {}))
  ([value writer options]
   (apply clojure.pprint/write value (mapcat identity (assoc options :stream writer)))))

(defn orchard-pprint
  "Alias of `pprint`; both are backed by `orchard.pp`."
  ([value writer]
   (orchard-pp value writer {}))
  ([value writer options]
   (orchard-pp value writer options)))

(def ^:private fipp-printer
  (delay (try-resolve 'fipp.edn/pprint "Fipp")))

(defn fipp-pprint
  ([value writer]
   (fipp-pprint value writer {}))
  ([value writer options]
   (if-some [fipp @fipp-printer]
     (binding [*out* writer]
       (fipp value options))
     ;; Default to orchard.pp if Fipp could not be loaded.
     (orchard-pp value writer options))))

(def ^:private puget-printer
  (delay (try-resolve 'puget.printer/pprint "Puget")))

(defn puget-pprint
  ([value writer]
   (puget-pprint value writer {}))
  ([value writer options]
   (if-some [puget @puget-printer]
     (binding [*out* writer]
       (puget value options))
     ;; Default to orchard.pp if Puget could not be loaded.
     (orchard-pp value writer options))))

(def ^:private zprint-printer
  (delay (try-resolve 'zprint.core/zprint "zprint")))

(defn zprint-pprint
  ([value writer]
   (zprint-pprint value writer {}))
  ([value writer options]
   (if-some [zprint @zprint-printer]
     (binding [*out* writer]
       (zprint value options))
     ;; Default to orchard.pp if zprint could not be loaded.
     (orchard-pp value writer options))))
