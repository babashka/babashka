(ns cider.nrepl.middleware.util.coerce
  "Coercion utilities for coercing bencoded maps.")

(defn- update-some
  [m k & args]
  (if (get m k)
    (apply update m k args)
    m))

(defn ns-query
  "Poke and prod at a bencoded ns-query until it is in the form that orchard
  expects."
  [ns-query]
  (-> ns-query
      (update-some :exactly
                   #(seq
                     (map (fn [ns-string]
                            (if-let [ns (find-ns (symbol ns-string))]
                              ns
                              (throw (ex-info "Namespace not found"
                                              {::id :namespace-not-found
                                               :namespace-string ns-string}))))
                          %)))
      (update :project? some?)
      (update :load-project-ns? (fn [x]
                                  (cond
                                    (= x []) false
                                    :else (some? x))))
      (update :has-tests? some?)
      (update-some :include-regexps #(map re-pattern %))
      (update-some :exclude-regexps #(map re-pattern %))))

(defn var-query
  [var-query]
  (-> var-query
      (update :ns-query ns-query)
      (update-some :exactly #_#(seq (keep (comp find-var symbol) %)) ;; BB-PATCH a var in an unloaded namespace replies namespace-not-found
                   #(seq (keep (fn [s]
                                 (let [s (symbol s)]
                                   (when-not (some-> (namespace s) symbol find-ns)
                                     (throw (ex-info "Namespace not found"
                                                     {::id :namespace-not-found
                                                      :namespace-string (namespace s)})))
                                   (find-var s)))
                               %)))
      (update :test? some?)
      (update :private? some?)
      (update-some :include-meta-key #(map keyword %))
      (update-some :exclude-meta-key #(map keyword %))
      (update-some :search re-pattern)
      (update-some :search-property keyword)
      (dissoc :manipulate-vars)))
