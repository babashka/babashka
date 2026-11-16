(ns cider.nrepl.middleware.util.coerce
  "Coercion utilities for coercing bencoded maps.")

(defn- update-some
  [m k & args]
  (if (get m k)
    (apply update m k args)
    m))

(defn- namespace-not-found [ns-string]
  (ex-info "Namespace not found"
           {::id :namespace-not-found
            :namespace-string ns-string}))

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
                              (throw (namespace-not-found ns-string))))
                          %)))
      (update :project? some?)
      (update :load-project-ns? (fn [x]
                                  (cond
                                    (= x []) false
                                    :else (some? x))))
      (update :has-tests? some?)
      (update-some :include-regexps #(map re-pattern %))
      (update-some :exclude-regexps #(map re-pattern %))))

(defn- find-var-named
  "The var `var-string` names, or nil if there's none. Throws namespace-not-found
  when its namespace isn't loaded, where `find-var` would throw an
  IllegalArgumentException."
  [var-string]
  (let [sym (symbol var-string)]
    (when-let [ns-string (namespace sym)]
      (when-not (find-ns (symbol ns-string))
        (throw (namespace-not-found ns-string)))
      (find-var sym))))

(defn var-query
  [var-query]
  (-> var-query
      (update :ns-query ns-query)
      ;; Empty when none of them are vars, since nil would mean every var.
      (update-some :exactly #(doall (keep find-var-named %)))
      (update :test? some?)
      (update :private? some?)
      (update-some :include-meta-key #(map keyword %))
      (update-some :exclude-meta-key #(map keyword %))
      (update-some :search re-pattern)
      (update-some :search-property keyword)
      (dissoc :manipulate-vars)))
