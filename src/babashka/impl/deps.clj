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

;; deps.clj caches the basis next to the classpath file.

(def ^:private current-basis (atom nil))

(defn reset-basis!
  "Clears the cached basis."
  []
  (reset! current-basis nil))

(defn- basis-file
  "Returns the basis file path for args."
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
  "Returns the basis at path, or nil if the file is missing or cannot be read."
  [path]
  (when (and path (fs/exists? path))
    (try (edn/read-string {:default (fn [_tag val] val)} (slurp path))
         (catch Exception _ nil))))

(defn- classpath-libs
  "Returns the set of libs on the basis classpath."
  [basis]
  (into #{} (keep (comp :lib-name val)) (:classpath basis)))

(def ^:private coord-keys
  [:mvn/version :git/url :git/sha :git/tag :local/root :deps/root :deps/manifest
   :exclusions])

(defn- pinned-deps
  "Returns dependency coordinates for libs on the basis classpath at their
  resolved versions."
  [basis]
  (let [on-classpath (classpath-libs basis)]
    (reduce-kv (fn [m lib coord]
                 (if (contains? on-classpath lib)
                   (assoc m lib (select-keys coord coord-keys))
                   m))
               {}
               (:libs basis))))

(defn- merge-basis
  "Merges the libs and classpath of basis into prev, so that a call records
  what it resolved without dropping what another call recorded."
  [prev basis]
  (if prev
    (-> prev
        (update :libs merge (:libs basis))
        (update :classpath merge (:classpath basis)))
    basis))

(defn- add-new-roots!
  "Adds roots from classpath, excluding roots already on the classpath."
  [classpath]
  (let [sep (re-pattern (java.util.regex.Pattern/quote cp/path-sep))
        known (set (str/split (or (System/getProperty "java.class.path") "") sep))
        fresh (remove known (str/split classpath sep))]
    (when (seq fresh)
      (cp/add-classpath (str/join cp/path-sep fresh)))))

;; We are optimizing for the 1-file script with deps scenario where people can
;; call this function to include e.g. {:deps {medley/medley
;; {:mvn/version "1.3.3"}}}. Optionally they can include aliases, to modify the
;; classpath.
(defn add-deps
  "Resolves dependencies from a deps.edn map and adds them to the classpath.
  Returns a sorted vector of added libs, or nil if none were added.
  Preserves versions of libs already on the classpath.

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
               deps-map (assoc deps-map :deps-root (str deps-root))
               ;; Existing versions take precedence over requested versions.
               deps-map (if-let [pinned (not-empty (pinned-deps @current-basis))]
                          (update deps-map :deps #(merge % pinned))
                          deps-map)]
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
                   cp (with-out-str (with-bindings bindings
                                      (apply deps/-main args)))
                   cp (str/trim cp)
                   cp (str/replace cp (re-pattern (str cp/path-sep "+$")) "")
                   basis (read-basis (with-bindings bindings (basis-file args)))]
               (add-new-roots! cp)
               ;; the libs this call added are the ones its own update
               ;; introduced, so a call running next to another reports
               ;; what it contributed and not what the other did
               (let [[prev now] (swap-vals! current-basis merge-basis basis)]
                 (not-empty (vec (sort (remove (classpath-libs prev)
                                               (classpath-libs now))))))))))))))

(def deps-namespace
  {'add-deps (sci/copy-var add-deps dns)
   'clojure (sci/copy-var bdeps/clojure dns)
   'merge-deps (sci/copy-var merge-deps dns)
   ;; undocumented
   'merge-defaults (sci/copy-var merge-default-deps dns {:name 'merge-defaults})})
