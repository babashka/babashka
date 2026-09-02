(ns babashka.mvn.tools-deps
  "The :mvn procurer for tools.deps, without Maven. Loaded after tools.deps
  loaded its own, so these methods win."
  (:require [babashka.fs :as fs]
            [babashka.mvn.coords :as coords]
            [babashka.mvn.metadata :as metadata]
            [babashka.mvn.pom :as pom]
            [babashka.mvn.repo :as repo]
            [babashka.mvn.settings :as settings]
            [babashka.mvn.version :as version]
            [clojure.string :as str]
            [clojure.tools.deps.extensions :as ext]
            [clojure.tools.deps.util.session :as session]))

(defn- settings []
  (session/retrieve :babashka.mvn/settings settings/read-settings))

(defn- repos [{:keys [mvn/repos]}]
  (session/retrieve [:babashka.mvn/repos repos] #(repo/remote-repos repos (settings))))

(defn- local-repo [config]
  (repo/local-repo config (settings)))

(defn- model-cache []
  (session/retrieve :babashka.mvn/models #(atom {})))

(defn- check-version [lib {:keys [mvn/version]}]
  (when (nil? version)
    (throw (ex-info (str "No :mvn/version specified for " lib) {}))))

;; POMs

(defn- pom-repos
  "Repositories a POM declares, after the configured ones. http ones are
  dropped, Maven blocks those by default."
  [config declared]
  (into (repos config)
        (comp (filter :url)
              (remove #(str/starts-with? (:url %) "http:"))
              (map (fn [{:keys [id url]}] (repo/remote-repo (settings) [id {:url url}]))))
        declared))

(defn- read-pom
  "POM text for a gav map, downloaded when needed. nil when no repository
  has it."
  [config {:keys [group artifact version]} declared-repos]
  (some-> (repo/resolve-file! (local-repo config) (pom-repos config declared-repos)
                              {:group group :artifact artifact :version version :extension "pom"})
          slurp))

(defn- pom-ctx [config]
  {:read-pom (partial read-pom config)
   :cache (model-cache)
   :basedir nil})

(defn- effective-model
  "The effective model for lib and coord, following relocations."
  [lib coord config]
  (let [ctx (pom-ctx config)
        [group artifact] (coords/lib->names lib)]
    (loop [gav {:group group :artifact artifact :version (:mvn/version coord)}
           hops 0]
      (let [text (read-pom config gav [])
            _ (when-not text
                (throw (ex-info (str "Unable to download: [" lib (pr-str (:mvn/version coord)) "]")
                                {:lib lib :coord coord})))
            model (pom/effective-model (pom/parse text) ctx)
            relocation (:relocation model)]
        (if (and relocation (< hops 10))
          (recur {:group (or (:group relocation) (:group gav))
                  :artifact (or (:artifact relocation) (:artifact gav))
                  :version (or (:version relocation) (:version gav))}
                 (inc hops))
          model)))))

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
           (throw (ex-info (str "Unable to download: [" lib (pr-str (:mvn/version coord)) "]")
                           {:lib lib :coord coord})))])))

;; Versions from metadata

(defn- artifact-versions [lib config]
  (let [[group artifact] (coords/lib->names lib)]
    (session/retrieve [:babashka.mvn/versions lib]
                      #(metadata/versions (local-repo config) (repos config)
                                          {:group group :artifact artifact}))))

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
  (let [specific (second (re-matches #"^\[([^,]*)]$" version))]
    (cond
      (contains? #{"RELEASE" "LATEST"} version)
      (let [{:keys [latest release]} (artifact-versions lib config)
            resolved (if (= "RELEASE" version) release latest)]
        (if resolved
          [lib (assoc coord :mvn/version resolved)]
          (throw (unresolved lib coord))))

      specific
      [lib (assoc coord :mvn/version specific)]

      (coords/version-range? version)
      (let [{:keys [versions]} (artifact-versions lib config)
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
  "read-pom for a POM on disk: a parent named by relativePath comes from
  disk when its coordinates match, the rest from the repositories."
  [config root]
  (fn [{:keys [group artifact version relative-path] :as gav} declared-repos]
    (let [rel (or relative-path "../pom.xml")
          f (let [f (fs/file root rel)] (if (fs/directory? f) (fs/file f "pom.xml") f))
          on-disk (when (fs/exists? f)
                    (let [text (slurp f)
                          raw (pom/parse text)]
                      (when (= [group artifact version]
                               [(or (:group raw) (get-in raw [:parent :group]))
                                (:artifact raw)
                                (or (:version raw) (get-in raw [:parent :version]))])
                        text)))]
      (or on-disk (read-pom config gav declared-repos)))))

(defn- local-model [{:keys [deps/root]} config]
  (let [root (str (fs/canonicalize root))
        text (slurp (fs/file root "pom.xml"))]
    (pom/effective-model (pom/parse text)
                         {:read-pom (read-local-pom config root)
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
