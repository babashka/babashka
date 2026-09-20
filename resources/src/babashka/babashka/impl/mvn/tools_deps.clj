(ns babashka.impl.mvn.tools-deps
  "The :mvn and :pom procurers for tools.deps, without Maven. Required by
  bb's stand-ins for clojure.tools.deps.extensions.maven and .pom, which
  tools.deps loads in place of its own."
  {:no-doc true}
  (:require [babashka.fs :as fs]
            [babashka.impl.mvn.coords :as coords]
            [babashka.impl.mvn.metadata :as metadata]
            [babashka.impl.mvn.pom :as pom]
            [babashka.impl.mvn.repo :as repo]
            [babashka.impl.mvn.settings :as settings]
            [babashka.impl.mvn.version :as version]
            [clojure.string :as str]
            [clojure.tools.deps.extensions :as ext]
            [clojure.tools.deps.util.session :as session]))

(defn- settings []
  (session/retrieve :babashka.impl.mvn/settings settings/read-settings))

;; session/retrieve is ConcurrentHashMap.computeIfAbsent, which throws
;; "Recursive update" when its function retrieves too, so look up first.
(defn- repos [{:keys [mvn/repos]}]
  (let [s (settings)]
    (session/retrieve [:babashka.impl.mvn/repos repos] #(repo/remote-repos repos s))))

(defn- local-repo [config]
  (repo/local-repo config))

(defn- model-cache []
  (session/retrieve :babashka.impl.mvn/models #(atom {})))

(defn- check-version [lib {:keys [mvn/version] :as coord}]
  (cond
    (nil? version)
    (throw (ex-info (str "No :mvn/version specified for " lib) {:lib lib :coord coord}))

    (not (string? version))
    (throw (ex-info (str "Invalid :mvn/version for " lib ": " (pr-str version))
                    {:lib lib :coord coord}))))

(defn- not-found-message
  "Returns the tools.deps message for a missing artifact. A -SNAPSHOT names
  the build and repository its metadata picks, other versions the first
  repository enabled for their release or snapshot policy. Present means
  the named build's file is in the local repository."
  [local-repo repos {:keys [group artifact extension classifier version] :as a}]
  (let [gav #(str group ":" artifact ":" extension (when classifier (str ":" classifier)) ":" %)
        picked (when (str/ends-with? version "-SNAPSHOT")
                 (metadata/resolve-snapshot local-repo repos a))
        [build repo] (if (map? (:repo picked))
                       [(:version picked) (:repo picked)]
                       [version (let [policy (if (coords/snapshot? version) :snapshots :releases)]
                                  (first (filter #(get-in % [policy :enabled]) repos)))])
        cached? (fs/exists? (fs/path local-repo (coords/version-dir a) (coords/file-name (assoc a :version build))))]
    (str "The following artifacts could not be resolved: " (gav version)
         (if cached? " (present, but unavailable)" " (absent)")
         ": Could not find artifact " (gav build)
         (when repo (str " in " (:id repo) " (" (:display-url repo) ")")))))

;; POMs

(defn- pom-repos
  "Returns the configured repositories, then the ones a POM declares,
  http: ones included."
  [config declared]
  (into (repos config)
        (comp (filter :url)
              (map (fn [{:keys [id url]}] (repo/remote-repo (settings) [id {:url url}]))))
        declared))

(defn- read-pom
  "POM text for a gav map, downloaded when needed. nil when no repository
  has it, or when the version is a range."
  [config {:keys [group artifact version]} declared-repos]
  (when-not (coords/version-range? version)
    (some-> (repo/resolve-file! (local-repo config) (pom-repos config declared-repos)
                                {:group group :artifact artifact :version version :extension "pom"})
            slurp)))

(defn- parent-version
  "The highest version the repositories list within a parent's version
  range. Throws when no version matches or the range is unbounded."
  [config {:keys [group artifact version]} declared-repos]
  (let [{:keys [versions]} (metadata/versions (local-repo config) (pom-repos config declared-repos)
                                              {:group group :artifact artifact}
                                              (metadata/range-nature version))
        highest (last (filter #(version/in-range? % version) versions))
        data {:group group :artifact artifact :version version}]
    (cond
      (nil? highest)
      (throw (ex-info (format "No versions matched the requested parent version range '%s'" version) data))

      (some (comp nil? :high) (version/parse-range version))
      (throw (ex-info (format "The requested parent version range '%s' does not specify an upper bound" version) data))

      :else highest)))

(defn- pom-ctx [config]
  {:read-pom (partial read-pom config)
   :resolve-version (partial parent-version config)
   :cache (model-cache)
   :basedir nil})

(defn- invalid? [e]
  (some #(#{:babashka.impl.mvn.pom/unreadable :babashka.impl.mvn.pom/invalid} (:type (ex-data %)))
        (take-while some? (iterate ex-cause e))))

(def ^:private no-descriptor
  {:dependencies [] :licenses []})

(defn- invalid-key [gav]
  [:babashka.impl.mvn/invalid-pom gav])

(defn- invalid-pom
  "no-descriptor for gav, whose POM does not parse or is invalid. Warns once
  per session."
  [gav e]
  (session/retrieve (invalid-key gav)
                    (fn []
                      (binding [*out* *err*]
                        (println (str "WARNING: The POM for " (:group gav) ":" (:artifact gav) ":" (:version gav)
                                      " is invalid, transitive dependencies will not be available: " (ex-message e))))
                      true))
  no-descriptor)

(defn- effective-model
  "The effective model for lib and coord, following relocations. Without
  dependencies when the POM is missing, or when it or a parent or BOM does
  not parse or is invalid."
  [lib coord config]
  (let [ctx (pom-ctx config)
        [group artifact] (coords/lib->names lib)]
    (loop [gav {:group group :artifact artifact :version (:mvn/version coord)}
           hops 0]
      (if-let [text (when-not (session/retrieve (invalid-key gav))
                      (read-pom config gav []))]
        (let [model (try (pom/effective-model (pom/parse text) (assoc ctx :coords gav))
                         (catch Exception e
                           (if (invalid? e)
                             (invalid-pom gav e)
                             (throw e))))
              relocation (:relocation model)]
          (if (and relocation (< hops 10))
            (recur {:group (or (:group relocation) (:group gav))
                    :artifact (or (:artifact relocation) (:artifact gav))
                    :version (or (:version relocation) (:version gav))}
                   (inc hops))
            model))
        no-descriptor))))

(defn- dep->data [{:keys [group artifact version type classifier scope optional exclusions]}]
  (let [extension (coords/type->extension type)
        classifier (or classifier (coords/type->classifier type))]
    [(symbol group (if (str/blank? classifier) artifact (str artifact "$" classifier)))
     (cond-> {:mvn/version version}
       (not= "jar" extension) (assoc :extension extension)
       scope (assoc :scope scope)
       optional (assoc :optional true)
       (seq exclusions) (assoc :exclusions (into #{} (map #(symbol (:group %) (:artifact %))) exclusions)))]))

(defn model-from-text
  "The effective model of a POM given as text, parents and BOMs from the
  repositories in config."
  [text config]
  (pom/effective-model (pom/parse text) (pom-ctx config)))

(defn model-deps
  "The compile and runtime dependencies of a model, as tools.deps data."
  [model]
  (into []
        (comp (filter #(contains? #{"compile" "runtime"} (:scope %)))
              (map dep->data))
        (pom/dependencies model)))

;; Extension methods

(defmethod ext/coord-type-keys :mvn
  [_type]
  #{:mvn/version})

(defmethod ext/dep-id :mvn
  [_lib coord _config]
  (select-keys coord [:mvn/version]))

(defmethod ext/manifest-type :mvn
  [_lib _coord _config]
  {:deps/manifest :mvn})

(defmethod ext/coord-summary :mvn
  [lib {:keys [mvn/version]}]
  (str lib " " version))

(defmethod ext/manifest-file :mvn
  [_lib _coord _manifest _config]
  nil)

(defmethod ext/coord-usage :mvn
  [_lib _coord _manifest _config]
  nil)

(defmethod ext/prep-command :mvn
  [_lib _coord _manifest _config]
  nil)

(defmethod ext/coord-usage :pom
  [_lib _coord _manifest _config]
  nil)

(defmethod ext/prep-command :pom
  [_lib _coord _manifest _config]
  nil)

(defmethod ext/lib-location :mvn
  [lib {:keys [mvn/version]} config]
  (let [[group-id artifact-id classifier] (coords/lib->names lib)]
    {:base (local-repo config)
     :path (str/join "/" (concat (str/split group-id #"\.") [artifact-id version]))
     :classifier classifier
     :type :mvn}))

(defmethod ext/coord-deps :mvn
  [lib coord _manifest config]
  (check-version lib coord)
  (into []
        (comp (map dep->data)
              (filter #(contains? #{"compile" "runtime"} (:scope (second %))))
              (remove (comp :optional second))
              (map #(update-in % [1] dissoc :scope :optional)))
        (pom/dependencies (effective-model lib coord config))))

(defmethod ext/coord-paths :mvn
  [lib {:keys [extension] :or {extension "jar"} :as coord} _manifest config]
  (check-version lib coord)
  (when (= "jar" extension)
    (let [artifact (coords/artifact lib coord)]
      [(or (repo/resolve-file! (local-repo config) (repos config) artifact)
           (throw (ex-info (not-found-message (local-repo config) (repos config) artifact)
                           {:lib lib :coord coord})))])))

;; Versions from metadata

(defn- artifact-versions
  ([lib config] (artifact-versions lib config :release))
  ([lib config nature]
   (let [[group artifact] (coords/lib->names lib)
         local (local-repo config)
         remotes (repos config)]
     (session/retrieve [:babashka.impl.mvn/versions lib nature]
                       #(metadata/versions local remotes {:group group :artifact artifact} nature)))))

(defn- unresolved [lib coord]
  (ex-info (str "Unable to resolve " lib " version: " (:mvn/version coord))
           {:lib lib :coord coord}))

(defmethod ext/find-versions :mvn
  [lib _coord _coord-type config]
  (let [{:keys [versions]} (artifact-versions lib config)]
    (when (seq versions)
      (into []
            (comp (remove #(str/ends-with? % "-SNAPSHOT"))
                  (map #(hash-map :mvn/version %)))
            versions))))

(defmethod ext/canonicalize :mvn
  [lib {:keys [mvn/version] :as coord} config]
  (check-version lib coord)
  (let [specific (second (re-matches #"^\[([^,]*)]$" version))]
    (cond
      (contains? #{"RELEASE" "LATEST"} version)
      (let [{:keys [latest release]} (artifact-versions lib config (if (= "RELEASE" version) :release :release-or-snapshot))
            ;; Aether falls back to release when the metadata names no latest,
            ;; which is what Clojars serves.
            resolved (if (= "RELEASE" version) release (or latest release))]
        (if resolved
          [lib (assoc coord :mvn/version resolved)]
          (throw (unresolved lib coord))))

      specific
      [lib (assoc coord :mvn/version specific)]

      (coords/version-range? version)
      (let [_ (try (version/parse-range version)
                   (catch clojure.lang.ExceptionInfo e
                     (let [{:keys [group artifact extension]} (coords/artifact lib coord)]
                       (throw (ex-info (str "Failed to resolve version range for "
                                            group ":" artifact ":" extension ":" version ": " (ex-message e))
                                       {:lib lib :coord coord} e)))))
            {:keys [versions]} (artifact-versions lib config (metadata/range-nature version))
            highest (last (filter #(version/in-range? % version) versions))]
        (if highest
          [lib (assoc coord :mvn/version highest)]
          (throw (unresolved lib coord))))

      :else
      [lib coord])))

(defmethod ext/compare-versions [:mvn :mvn]
  [lib coord-x coord-y _config]
  (check-version lib coord-x)
  (check-version lib coord-y)
  (version/compare-versions (:mvn/version coord-x) (:mvn/version coord-y)))

(defn- license [model]
  (let [{:keys [name url]} (first (:licenses model))]
    (when (or name url)
      (cond-> {} name (assoc :name name) url (assoc :url url)))))

(defmethod ext/license-info :mvn
  [lib coord config]
  (check-version lib coord)
  (license (effective-model lib coord config)))

;; Local pom.xml manifests, for :local/root and git deps without a deps.edn

(defn- read-local-pom
  "read-pom for a POM on disk. The parent at relativePath, relative to the
  POM that declares it, comes from disk when its coordinates match or its
  version is in the declared range. Other POMs come from the repositories."
  [config]
  (fn [{:keys [group artifact version relative-path basedir] :as gav} declared-repos]
    (let [f (when basedir
              (let [f (fs/file basedir (or relative-path "../pom.xml"))]
                (if (fs/directory? f) (fs/file f "pom.xml") f)))
          on-disk (when (and f (fs/exists? f))
                    (let [text (slurp f)
                          [g a v] (pom/coordinates (pom/parse text))]
                      (when (and (= [group artifact] [g a])
                                 (if (coords/version-range? version)
                                   (version/in-range? v version)
                                   (= version v)))
                        {:text text :basedir (str (fs/parent (fs/canonicalize f))) :version v
                         :file (str (fs/canonicalize f))})))]
      (or on-disk (read-pom config gav declared-repos)))))

(defn- local-model [{:keys [deps/root]} config]
  (let [root (str (fs/canonicalize root))
        text (slurp (fs/file root "pom.xml"))]
    (pom/effective-model (pom/parse text)
                         {:read-pom (read-local-pom config)
                          :resolve-version (partial parent-version config)
                          :cache (model-cache)
                          :basedir root})))

(defmethod ext/coord-deps :pom
  [_lib coord _manifest config]
  (into []
        (comp (filter #(contains? #{"compile" "runtime"} (:scope %)))
              (map dep->data))
        (pom/dependencies (local-model coord config))))

(defn- build-helper-paths
  "Source and resource directories the build-helper-maven-plugin adds."
  [{:keys [build]}]
  (let [plugin (first (filter #(and (= "org.codehaus.mojo" (:group %))
                                    (= "build-helper-maven-plugin" (:artifact %)))
                              (:plugins build)))
        with-goal (fn [goal k]
                    (mapcat k (filter #(some #{goal} (:goals %)) (:executions plugin))))]
    (when plugin
      (concat (with-goal "add-source" :sources)
              (with-goal "add-resource" :resources)))))

(defmethod ext/coord-paths :pom
  [_lib {:keys [deps/root] :as coord} _manifest config]
  (let [root (str (fs/canonicalize root))
        model (local-model coord config)
        build (:build model)
        canonical (fn [p] (str (fs/canonicalize (if (fs/absolute? p) p (fs/file root p)))))
        resources (let [rs (:resources build)] (if (seq rs) rs ["src/main/resources"]))]
    (->> (concat [(or (:source-directory build) "src/main/java")
                  "src/main/clojure"]
                 resources
                 (build-helper-paths model))
         (remove nil?)
         (map canonical)
         distinct)))

(defmethod ext/manifest-file :pom
  [_lib {:keys [deps/root]} _manifest _config]
  (str (fs/absolutize (fs/file root "pom.xml"))))

(defmethod ext/license-info-mf :pom
  [_lib coord _manifest config]
  (license (local-model coord config)))
