(ns orchard.query
  "Query for namespaces and vars"
  (:require
   [orchard.meta :as m]
   [orchard.namespace :as ns]))

(defn namespaces
  "Takes a map containing these keys:

  `:exactly` The namespaces returned should be exactly these.
  `:project?` If true, the namespaces should all belong to the project.
  `:load-project-ns?` If true, the project namespaces will be loaded if they are not already.
  `:has-tests?` If true, only return namespaces containing tests.
  `:include-regexps` A list of regexps which the namespaces must match.
  `:exclude-regexps` A list of regexps which the namespaces must not match.

  Returns a list of namespaces as `find-ns` would return them on the host."
  [{:keys [project? load-project-ns?] :as ns-query}]
  (cond->> (cond
             (:exactly ns-query)
             (:exactly ns-query)

             project?
             (if load-project-ns?
               (map find-ns (ns/load-project-namespaces))
               (map find-ns (ns/loaded-project-namespaces)))

             :else
             (all-ns))

    true
    (remove ns/inlined-dependency?)

    (:has-tests? ns-query)
    (filter ns/has-tests?)

    (:include-regexps ns-query)
    (filter #(ns/internal-namespace? % (:include-regexps ns-query)))

    (:exclude-regexps ns-query)
    (remove #(ns/internal-namespace? % (:exclude-regexps ns-query)))))

(defn vars
  "Takes a map containing these keys:
  `:ns-query` A ns-query as `namespaces` takes, used to filter namespaces which contain vars.
  `:private?` Include private vars in the results.
  `:test?` Filter to vars which are also tests.
  `:include-meta-key` A list of keywords searched for in the var's metadata, vars matching will be included.
  `:exclude-meta-key` A list of keywords searched for in the var's metadata, vars matching will be excluded.
  `:search` A regex to use for filtering vars, matches against `:search-property`.
  `:search-property` Either :doc or :name, defaults to `:name`.
  `:manipulate-vars` A callback run with the list of namespaces return by namespaces and the vars found within. Can be used to manipulate the list of vars before they are filtered.

  Returns a list of vars, as the type that `var` would return on the host."
  [{:keys [ns-query

           private?
           test?

           include-meta-key
           exclude-meta-key

           search
           search-property

           manipulate-vars]
    :as var-query}]
  (let [ns-vars (if private? ns-interns ns-publics)
        nss (namespaces ns-query)]
    (cond->> (or (:exactly var-query)
                 (mapcat (comp vals ns-vars) nss))

      manipulate-vars
      (manipulate-vars nss)

      search
      (filter (comp (partial re-find search)
                    (case (or search-property :name)
                      :doc m/var-doc
                      :name m/var-name)))

      test?
      (filter #(:test (m/meta+ %)))

      include-meta-key
      (filter #((apply some-fn include-meta-key) (m/meta+ %)))

      exclude-meta-key
      (remove #((apply some-fn exclude-meta-key) (m/meta+ %))))))
