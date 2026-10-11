(ns cider.nrepl.middleware.util.meta
  "Utility functions for extracting and manipulating metadata.")

(def relevant-meta-keys
  "Metadata keys that are useful to us.
  This is used so that we don't crowd the ns cache with useless or
  redudant information, such as :name and :ns."
  [:indent :deprecated :macro :arglists :test :doc :fn
   :cider/instrumented :style/indent :orchard.trace/traced
   :orchard.profile/profiled])

(defn relevant-meta
  "Filter the entries in map m by `relevant-meta-keys` and non-nil values."
  [m]
  (into {}
        (keep #(when-let [v (get m %)] [% (pr-str v)]))
        relevant-meta-keys))
