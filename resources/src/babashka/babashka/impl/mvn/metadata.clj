(ns babashka.impl.mvn.metadata
  "maven-metadata.xml: the versions of an artifact, and the timestamped
  file behind a SNAPSHOT version. Cached in the local repository under
  Aether's names, maven-metadata-<repoId>.xml, with the update policy."
  {:no-doc true}
  (:require [babashka.fs :as fs]
            [babashka.impl.mvn.coords :as coords]
            [babashka.impl.mvn.http :as http]
            [babashka.impl.mvn.version :as version]
            [babashka.impl.mvn.xml :as x]))

(defn- parse-artifact-metadata [s]
  (let [root (x/parse s)
        versioning (x/child root "versioning")]
    {:latest (x/child-text versioning "latest")
     :release (x/child-text versioning "release")
     :versions (mapv x/text (some-> (x/child versioning "versions") (x/children "version")))}))

(defn parse-snapshot-metadata
  "The versioning of a snapshot's maven-metadata.xml: :last-updated,
  :snapshot with :timestamp and :build-number when the element is present,
  and :snapshot-versions with :classifier, :extension, :value and :updated."
  [s]
  (let [root (x/parse s)
        versioning (x/child root "versioning")
        snapshot (x/child versioning "snapshot")]
    {:last-updated (x/child-text versioning "lastUpdated")
     :snapshot (when snapshot
                 {:timestamp (x/child-text snapshot "timestamp")
                  :build-number (some-> (x/child-text snapshot "buildNumber") parse-long)})
     :snapshot-versions (mapv (fn [sv]
                                {:classifier (x/child-text sv "classifier")
                                 :extension (x/child-text sv "extension")
                                 :value (x/child-text sv "value")
                                 :updated (x/child-text sv "updated")})
                              (some-> (x/child versioning "snapshotVersions") (x/children "snapshotVersion")))}))

(defn- local-midnight-millis []
  (-> (java.time.LocalDate/now) (.atStartOfDay (java.time.ZoneId/systemDefault)) .toInstant .toEpochMilli))

(defn- stale?
  "Whether a cached metadata file should be refreshed under the policy,
  after Maven's DefaultUpdatePolicyAnalyzer: daily means before today's
  local midnight, a number is an interval in minutes."
  [file {:keys [update]}]
  (cond
    (not (fs/exists? file)) true
    (= :always update) true
    (= :never update) false
    :else (let [modified (.toMillis (fs/last-modified-time file))]
            (if (= :daily update)
              (> (local-midnight-millis) modified)
              (> (- (System/currentTimeMillis) (* 60000 (long update))) modified)))))

(defn- cached-text!
  "Metadata text from repo for the directory rel, from the cache when fresh,
  fetched and cached otherwise. nil when the repository has none."
  [local-repo {:keys [id url auth proxy headers]} rel policy]
  (let [file (fs/file local-repo rel (str "maven-metadata-" id ".xml"))]
    (if (stale? file policy)
      (when-let [text (http/fetch (str url rel "/maven-metadata.xml")
                                  {:auth auth :proxy proxy :headers headers :repo-id id :label (str rel "/maven-metadata.xml")})]
        (fs/create-dirs (fs/parent file))
        ;; a parallel reader never sees a partial file
        (let [tmp (http/temp-file file)]
          (spit tmp text)
          (http/move-into-place! tmp (str file)))
        text)
      (slurp file))))

(defn versions
  "All versions of the artifact across the enabled repositories and the
  local repository's maven-metadata-local.xml, in Maven order, with :latest
  and :release from the first repository that names them."
  [local-repo repos artifact]
  (let [rel (coords/artifact-dir artifact)
        installed (let [f (fs/file local-repo rel "maven-metadata-local.xml")]
                    (when (fs/exists? f)
                      (parse-artifact-metadata (slurp f))))
        found (concat (keep (fn [repo]
                              (when (get-in repo [:releases :enabled])
                                (some-> (cached-text! local-repo repo rel (:releases repo))
                                        parse-artifact-metadata)))
                            repos)
                      (when installed [installed]))]
    {:versions (->> found
                    (mapcat :versions)
                    distinct
                    (sort version/compare-versions)
                    vec)
     :latest (some :latest found)
     :release (some :release found)}))

(defn- merge-info
  "Stores version under key when key has no entry or updated is later than
  the entry's."
  [infos key updated version repo]
  (let [info (get infos key)]
    (if (or (nil? info) (and updated (pos? (compare updated (:updated info)))))
      (assoc infos key {:updated (or updated "") :version version :repo repo})
      infos)))

(defn- snapshot-key [classifier extension]
  (str (or classifier "") ":" (or extension "")))

(defn- merge-versioning
  "Adds the entries of one metadata file to infos, keyed by classifier and
  extension, plus a generic entry from <snapshot> when the file lists no
  snapshot versions."
  [infos {:keys [last-updated snapshot snapshot-versions]} version repo]
  (let [infos (reduce (fn [infos {:keys [classifier extension value updated]}]
                        (if (seq value)
                          (merge-info infos (snapshot-key classifier extension) updated value repo)
                          infos))
                      infos
                      snapshot-versions)
        {:keys [timestamp build-number]} snapshot]
    (if (and snapshot (empty? snapshot-versions))
      (merge-info infos :snapshot last-updated
                  (if (and timestamp build-number (pos? build-number))
                    (str (subs version 0 (- (count version) (count "-SNAPSHOT"))) "-" timestamp "-" build-number)
                    version)
                  repo)
      infos)))

(defn- local-versioning
  "The versioning of the local repository's maven-metadata-local.xml for a
  snapshot version, nil without one. A build number there resets the entry
  to a local copy."
  [local-repo rel]
  (let [f (fs/file local-repo rel "maven-metadata-local.xml")]
    (when (fs/exists? f)
      (let [{:keys [snapshot] :as versioning} (parse-snapshot-metadata (slurp f))]
        (if (some-> snapshot :build-number pos?)
          {:last-updated (:last-updated versioning) :snapshot {} :snapshot-versions []}
          versioning)))))

(defn resolve-snapshot
  "Returns the build of a -SNAPSHOT artifact with the newest metadata entry
  for its classifier and extension, across the local repository and the
  enabled repositories, as {:version v :repo r}. :repo is nil for the local
  repository. Returns the base version with :repo :none when no metadata
  names a build."
  [local-repo repos {:keys [version classifier extension] :as artifact}]
  (let [rel (coords/version-dir artifact)
        key (snapshot-key classifier extension)
        infos (reduce (fn [infos repo]
                        (if-let [versioning (and (get-in repo [:snapshots :enabled])
                                                 (some-> (cached-text! local-repo repo rel (:snapshots repo))
                                                         parse-snapshot-metadata))]
                          (merge-versioning infos versioning version repo)
                          infos))
                      (if-let [versioning (local-versioning local-repo rel)]
                        (merge-versioning {} versioning version nil)
                        {})
                      repos)
        generic (get infos :snapshot)
        specific (get infos key)
        chosen (if (or (nil? specific)
                       (and generic
                            (pos? (compare (:updated generic) (:updated specific)))
                            (not= (:repo generic) (:repo specific))))
                 generic
                 specific)]
    (or (some-> chosen (select-keys [:version :repo]))
        {:version version :repo :none})))
