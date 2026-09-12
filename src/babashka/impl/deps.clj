(ns babashka.impl.deps
  (:require [babashka.deps :as bdeps]
            [babashka.fs :as fs]
            [babashka.impl.classpath :as cp]
            [babashka.impl.common :refer [bb-edn]]
            [babashka.impl.tools-deps :as tools-deps]
            [babashka.process :as process]
            [borkdude.deps :as deps]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [sci.core :as sci]))

(def dns (sci/create-ns 'babashka.deps nil))

;;;; merge deps.edn files

(defn- merge-or-replace
  "If maps, merge, otherwise replace"
  [& vals]
  (when (some identity vals)
    (reduce (fn [ret val]
              (if (and (map? ret) (map? val))
                (merge ret val)
                (or val ret)))
            nil vals)))

(defn merge-deps
  "Merge multiple deps edn maps from left to right into a single deps edn map."
  [deps-edn-maps]
  (apply merge-with merge-or-replace (remove nil? deps-edn-maps)))

(defn- merge-defaults [deps defaults]
  (let [overriden (select-keys deps (keys defaults))
        overriden-deps (keys overriden)
        defaults (select-keys defaults overriden-deps)]
    (merge deps defaults)))

(defn merge-default-deps [deps-map defaults]
  (let [paths (into [[:deps]]
                    (map (fn [alias]
                           [:aliases alias])
                         (keys (:aliases deps-map))))]
    (reduce
     (fn [acc path]
       (update-in acc path merge-defaults defaults))
     deps-map
     paths)))

#_(merge-default-deps '{:deps {medley/medley nil}
                        :aliases {:foo {medley/medley nil}}}
                      '{medley/medley {:mvn/version "1.3.0"}})

;;;; end merge edn files


;;;; basis

;; bb has no clojure.basis system property, so the basis behind the classpath
;; is read from the file deps.clj caches next to the classpath file.

(def ^:private current-basis (atom nil))

(defn reset-basis!
  "Forgets the basis. A run starts from the classpath its own options give
  it, so what an earlier run in the same process resolved does not carry
  over."
  []
  (reset! current-basis nil))

(defn- basis-file
  "Returns the path of the basis file for args. deps.clj writes it whenever
  it computes a classpath, and reuses it silently when the cache is warm,
  so a warm run derives the path the way deps.clj derives it."
  [args]
  (or (tools-deps/take-basis-file!)
      (let [cli-opts (deps/parse-cli-opts args)
            config-dir (deps/get-config-dir)
            install-dir (deps/get-install-dir)
            deps-edn (deps/get-local-deps-edn {:cli-opts cli-opts})
            config-paths (deps/get-config-paths {:cli-opts cli-opts
                                                 :deps-edn deps-edn
                                                 :config-dir config-dir
                                                 :install-dir install-dir})
            {:keys [cache-dir cache-dir-key]}
            (deps/get-cache-dir* {:deps-edn deps-edn :config-dir config-dir})
            checksum (deps/get-checksum {:cli-opts cli-opts
                                         :config-paths config-paths
                                         :cache-dir-key cache-dir-key})]
        (deps/get-basis-file {:cache-dir cache-dir :checksum checksum}))))

(defn- read-basis
  "Returns the basis at path, or nil when it is absent or damaged. The
  classpath is added either way, so a basis that will not read costs the
  return value and nothing else."
  [path]
  (when (and path (fs/exists? path))
    (try (edn/read-string {:default (fn [_tag val] val)} (slurp path))
         (catch Exception _ nil))))

(defn- classpath-libs
  "Returns the libs of basis that contribute to its classpath. A lib an
  alias blanks out, such as the Clojure jar babashka replaces with its own,
  stays in :libs but reaches no classpath root."
  [basis]
  (into #{} (keep (comp :lib-name val)) (:classpath basis)))

(defn- add-libs!
  "Resolves lib-coords against the libs already on the classpath, adds the
  ones that are new and returns them sorted. A lib already present keeps
  the version it has, as clojure.repl.deps/add-libs leaves it."
  [lib-coords basis getenv]
  (let [existing (:libs basis)
        wanted (into {} (remove (fn [[lib _]] (contains? existing lib))) lib-coords)]
    (when (seq wanted)
      (let [procurer (dissoc basis :basis-config :paths :deps :aliases :argmap
                             :classpath :classpath-roots :libs)
            {:keys [added]} (tools-deps/resolve-added-libs
                             {:existing existing :add wanted :procurer procurer}
                             getenv)]
        (when (seq added)
          (cp/add-classpath (str/join cp/path-sep (mapcat :paths (vals added))))
          (swap! current-basis update :libs merge added)
          (vec (sort (keys added))))))))

;; We are optimizing for the 1-file script with deps scenario where people can
;; call this function to include e.g. {:deps {medley/medley
;; {:mvn/version "1.3.3"}}}. Optionally they can include aliases, to modify the
;; classpath.
(defn add-deps
  "Resolves dependencies from a deps.edn map and adds them to the classpath.
  Returns the libs added, sorted, or nil when none were. A lib already on
  the classpath keeps the version it has and is not added again.

  Options: :aliases selects aliases by keyword, :force recomputes the
  classpath, :env replaces the environment, and :extra-env adds overrides.

  Set :deps-resolver in the deps map to :bb for in-process resolution or
  :jvm to use Java. Defaults to :deps-resolver in bb.edn, then
  BABASHKA_DEPS_RESOLVER, then bb."
  ([deps-map] (add-deps deps-map nil))
  ([deps-map {:keys [:aliases :env :extra-env :force]}]
   (let [deps-root (:deps-root @bb-edn)
         ;; tasks and scripts inherit the project's resolver
         resolver (or (:deps-resolver deps-map) (:deps-resolver @bb-edn))]
     (when-let [paths (:paths deps-map)]
       (let [paths (if deps-root
                     (let [deps-root (fs/absolutize deps-root)
                           paths (mapv #(str (fs/file deps-root %)) paths)]
                       paths)
                     paths)]
         (cp/add-classpath (str/join cp/path-sep paths))))
     (let [need-deps? (or (seq (:deps deps-map))
                          (and (:aliases deps-map)
                               aliases))]
       (when need-deps?
         (let [deps-map (dissoc deps-map
                                ;; paths are added manually above
                                ;; extra-paths are added as :paths in tasks
                                :paths :tasks :raw :file :deps-root
                                :min-bb-version :deps-resolver)
               ;; associate deps-root to avoid cache conflict between different
               ;; bb.edns with relative local/roots by the same name NOTE:
               ;; deps-root is nil when bb.edn isn't used, so clashes may still
               ;; happen with dynamic add-deps, but at least we don't invoke
               ;; clojure CLI's java process each time we call a script from a
               ;; different directory.
               deps-map (assoc deps-map :deps-root (str deps-root))]
           (binding [*print-namespace-maps* false]
             (let [deps-map (assoc-in deps-map [:aliases :org.babashka/defaults]
                                      {:replace-paths [] ;; babashka sets paths manually
                                       :classpath-overrides (cond->
                                                             '{org.clojure/clojure ""
                                                               org.clojure/spec.alpha ""}
                                                              ;; only remove core specs when they are not mentioned in deps map
                                                              (not (str/includes? (str deps-map) "org.clojure/core.specs.alpha"))
                                                              (assoc 'org.clojure/core.specs.alpha ""))})
                   args (list "-Srepro" ;; do not include deps.edn from user config
                              "-Spath" "-Sdeps" (str deps-map)
                              "-Sdeps-file" "__babashka_no_deps_file__.edn") ;; we reset deps file so the local deps.edn isn't used
                   args (if force (cons "-Sforce" args) args)
                   args (concat args [(str "-A:" (str/join ":" (cons ":org.babashka/defaults" aliases)))])
                   getenv (bdeps/getenv-fn env extra-env)
                   make-classpath-fn (bdeps/make-classpath-fn (when deps-root (str deps-root)) getenv resolver)
                   bindings (cond->
                             {#'deps/*getenv-fn* getenv
                              #'deps/*aux-process-fn* (fn [{:keys [cmd out]}]
                                                       (process/shell
                                                        {:cmd cmd
                                                         :out out
                                                         :env env
                                                         :dir (when deps-root (str deps-root))
                                                         :extra-env extra-env}))
                              #'deps/*exit-fn* (fn [{:keys [message]}]
                                                 (when message
                                                   (throw (Exception. message))))}
                              make-classpath-fn (assoc #'deps/*make-classpath-fn* make-classpath-fn)
                              deps-root (assoc #'deps/*dir* (str deps-root)))
                   basis @current-basis]
               (if (and (not force)
                        (empty? aliases)
                        (seq (:libs basis))
                        (seq (:deps deps-map)))
                 (add-libs! (:deps deps-map) basis getenv)
                 (let [cp (with-out-str (with-bindings bindings
                                          (apply deps/-main args)))
                       cp (str/trim cp)
                       cp (str/replace cp (re-pattern (str cp/path-sep "+$")) "")
                       basis (read-basis (with-bindings bindings (basis-file args)))]
                   (cp/add-classpath cp)
                   (reset! current-basis basis)
                   (not-empty (vec (sort (classpath-libs basis))))))))))))))

(def deps-namespace
  {'add-deps (sci/copy-var add-deps dns)
   'clojure (sci/copy-var bdeps/clojure dns)
   'merge-deps (sci/copy-var merge-deps dns)
   ;; undocumented
   'merge-defaults (sci/copy-var merge-default-deps dns {:name 'merge-defaults})})
