(ns babashka.mvn.tools-deps
  "The :mvn procurer for tools.deps, without Maven. Loaded after tools.deps
  loaded its own, so these methods win."
  (:require [babashka.mvn.coords :as coords]
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

;; Extension methods

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

(defmethod ext/compare-versions [:mvn :mvn]
  [lib coord-x coord-y _config]
  (check-version lib coord-x)
  (check-version lib coord-y)
  (version/compare-versions (:mvn/version coord-x) (:mvn/version coord-y)))

(defmethod ext/license-info :mvn
  [lib coord config]
  (check-version lib coord)
  (let [{:keys [name url]} (first (:licenses (effective-model lib coord config)))]
    (when (or name url)
      (cond-> {} name (assoc :name name) url (assoc :url url)))))
