(require '[clojure.string :as str])
(let [ns-maps  (->> (all-ns)
                 (map (fn [nmspc] [(ns-name nmspc) (ns-publics nmspc)]))
                 (into {})) ; a map of { ns-name {symbol var, ...}}
      ns-maps (update-in ns-maps ['user] #(dissoc % '*input*))] ; *input* is a special case that we'll skip over
   (->>
     (for [[ns-nm _] ns-maps
           [sym vr]  (ns-maps ns-nm)
           :let [{var-meta-ns :ns, var-meta-name :name} (meta vr)
                 var-meta-ns-name (some-> var-meta-ns ns-name)]]
       ; build a seq of maps containing the ns/symbol from the ns and the ns/symbol from the var's metadata
       {:actual-ns ns-nm :actual-ns-symbol sym :var-meta-ns var-meta-ns-name :var-meta-name var-meta-name})
     (remove #(and (= (:actual-ns %) (:var-meta-ns %)) (= (:actual-ns-symbol %) (:var-meta-name %))))))
