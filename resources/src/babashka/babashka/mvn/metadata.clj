(ns babashka.mvn.metadata
  "maven-metadata.xml: the versions of an artifact, and the timestamped
  file behind a SNAPSHOT version. Cached in the local repository under
  Aether's names, maven-metadata-<repoId>.xml, with the update policy."
  (:require [babashka.fs :as fs]
            [babashka.mvn.coords :as coords]
            [babashka.mvn.http :as http]
            [babashka.mvn.version :as version]
            [babashka.mvn.xml :as x]
            [clojure.string :as str]))

(defn- parse-artifact-metadata [s]
  (let [root (x/parse s)
        versioning (x/child root "versioning")]
    {:latest (x/child-text versioning "latest")
     :release (x/child-text versioning "release")
     :versions (mapv x/text (some-> (x/child versioning "versions") (x/children "version")))}))

(defn- parse-snapshot-metadata [s]
  (let [root (x/parse s)
        versioning (x/child root "versioning")
        snapshot (x/child versioning "snapshot")]
    {:timestamp (x/child-text snapshot "timestamp")
     :build-number (x/child-text snapshot "buildNumber")
     :local-copy (= "true" (x/child-text snapshot "localCopy"))
     :snapshot-versions (mapv (fn [sv]
                                {:classifier (x/child-text sv "classifier")
                                 :extension (x/child-text sv "extension")
                                 :value (x/child-text sv "value")})
                              (some-> (x/child versioning "snapshotVersions") (x/children "snapshotVersion")))}))

(defn- stale?
  "Whether a cached metadata file should be refreshed under the policy."
  [file {:keys [update]}]
  (cond
    (not (fs/exists? file)) true
    (= :always update) true
    (= :never update) false
    :else (let [age-minutes (/ (- (System/currentTimeMillis)
                                  (.toMillis (fs/last-modified-time file)))
                               60000.0)
                limit (if (= :daily update) 1440 (long update))]
            (> age-minutes limit))))

(defn- cached-text!
  "Metadata text from repo for the directory rel, from the cache when fresh,
  fetched and cached otherwise. nil when the repository has none."
  [local-repo {:keys [id url auth proxy]} rel policy]
  (let [file (fs/file local-repo rel (str "maven-metadata-" id ".xml"))]
    (if (stale? file policy)
      (when-let [text (http/fetch (str url rel "/maven-metadata.xml") {:auth auth :proxy proxy})]
        (fs/create-dirs (fs/parent file))
        (spit file text)
        text)
      (slurp file))))

(defn versions
  "All versions of the artifact across the enabled repositories, in Maven
  order, with :latest and :release from the first repository that names
  them."
  [local-repo repos artifact]
  (let [rel (coords/artifact-dir artifact)
        found (keep (fn [repo]
                      (when (get-in repo [:releases :enabled])
                        (some-> (cached-text! local-repo repo rel (:releases repo))
                                parse-artifact-metadata)))
                    repos)]
    {:versions (->> found
                    (mapcat :versions)
                    distinct
                    (sort version/compare-versions)
                    vec)
     :latest (some :latest found)
     :release (some :release found)}))

(defn snapshot-file-name
  "The timestamped file name behind a -SNAPSHOT version in repo, or nil when
  the repository has no such snapshot."
  [local-repo repo {:keys [version classifier] :as artifact}]
  (let [rel (coords/version-dir artifact)]
    (when-let [{:keys [timestamp build-number local-copy snapshot-versions]}
               (some-> (cached-text! local-repo repo rel (:snapshots repo))
                       parse-snapshot-metadata)]
      (let [value (or (some (fn [{:keys [extension] :as sv}]
                              (when (and (= extension (:extension artifact))
                                         (= (:classifier sv) classifier))
                                (:value sv)))
                            snapshot-versions)
                      (when (and timestamp build-number (not local-copy))
                        (str (str/replace version #"-SNAPSHOT$" "") "-" timestamp "-" build-number))
                      version)]
        (coords/file-name (assoc artifact :version value))))))
