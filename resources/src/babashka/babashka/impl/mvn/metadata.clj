(ns babashka.impl.mvn.metadata
  "maven-metadata.xml: the versions of an artifact, and the timestamped
  file behind a SNAPSHOT version. Cached in the local repository under
  Aether's names, maven-metadata-<repoId>.xml, with the update policy."
  {:no-doc true}
  (:require [babashka.fs :as fs]
            [babashka.impl.mvn.coords :as coords]
            [babashka.impl.mvn.http :as http]
            [babashka.impl.mvn.version :as version]
            [babashka.impl.mvn.xml :as x])
  (:import [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.nio.file OpenOption StandardOpenOption]
           [java.security MessageDigest]))

;; tools.deps resolves in parallel, and parents and BOMs are shared, so two
;; threads can want the same file. One monitor per path.
(def ^:private locks (atom {}))

(defn lock-for [path]
  (let [path (str (fs/normalize (fs/absolutize path)))]
    (or (get @locks path)
        (get (swap! locks update path #(or % (Object.))) path))))

;; Tracking files: a monitor per path for threads, a FileChannel lock for processes.

(defn- read-channel [^FileChannel ch]
  (let [buf (ByteBuffer/allocate (int (.size ch)))]
    (.position ch 0)
    (loop []
      (when (and (.hasRemaining buf) (pos? (.read ch buf)))
        (recur)))
    (String. (.array buf) 0 (.position buf) "UTF-8")))

(defn read-tracking-file
  "Returns the text of a tracking file, nil when it does not exist."
  [file]
  (let [path (fs/path file)]
    #_{:clj-kondo/ignore [:locking-suspicious-lock]}
    (locking (lock-for (str path))
      (when (fs/exists? path)
        (with-open [ch (FileChannel/open path (into-array OpenOption [StandardOpenOption/READ]))]
          (.lock ch 0 Long/MAX_VALUE true)
          (read-channel ch))))))

(defn update-tracking-file!
  "Replaces the text of a tracking file with (f text). text is \"\" for a new file."
  [file f]
  (let [path (fs/path file)]
    #_{:clj-kondo/ignore [:locking-suspicious-lock]}
    (locking (lock-for (str path))
      (with-open [ch (FileChannel/open path (into-array OpenOption [StandardOpenOption/READ
                                                                     StandardOpenOption/WRITE
                                                                     StandardOpenOption/CREATE]))]
        (.lock ch)
        (let [old (read-channel ch)
              new (f old)]
          (when (not= old new)
            (.truncate ch 0)
            (let [buf (ByteBuffer/wrap (.getBytes ^String new "UTF-8"))]
              (loop []
                (when (.hasRemaining buf)
                  (.write ch buf)
                  (recur))))))))))

(defn- parse-artifact-metadata [s]
  (let [root (x/parse s)
        versioning (x/child root "versioning")]
    {:latest (x/child-text versioning "latest")
     :release (x/child-text versioning "release")
     ;; an empty <version/> element, which a local install can write, is not a version
     :versions (into [] (keep x/text) (some-> (x/child versioning "versions") (x/children "version")))}))

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
  "Whether a last update at millis calls for a refresh under the policy,
  after Maven's DefaultUpdatePolicyAnalyzer: daily means before today's
  local midnight, a number is an interval in minutes."
  [millis {:keys [update]}]
  (cond
    (= :always update) true
    (= :never update) false
    (= :daily update) (> (local-midnight-millis) millis)
    :else (> (- (System/currentTimeMillis) (* 60000 (long update))) millis)))

;; Update checks, after Aether's DefaultUpdateCheckManager: the outcome of
;; each transfer goes into resolver-status.properties next to the metadata.

(defn- auth-digest
  "Aether's AuthenticationDigest of a username and password, \"\" without a
  username."
  [username password]
  (if username
    (let [md (MessageDigest/getInstance "SHA-1")]
      (.update md (.getBytes "username" "UTF-8"))
      (.update md (.getBytes ^String username "UTF-8"))
      (.update md (.getBytes "password" "UTF-8"))
      (when password
        (.update md (.getBytes ^String password "UTF-16BE")))
      (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md))))
    ""))

(defn- transfer-key [file {:keys [id url auth proxy]}]
  (str (fs/file-name file) "/"
       (when proxy
         (str (auth-digest (:username proxy) (:password proxy)) "@" (:host proxy) ":" (:port proxy) ">"))
       (auth-digest (first auth) (second auth)) "@default-" id "-" url))

(defn- status-file [file]
  (fs/file (fs/parent file) "resolver-status.properties"))

(defn- load-properties ^java.util.Properties [text]
  (doto (java.util.Properties.)
    (.load (java.io.StringReader. (or text "")))))

(defn- last-updated
  "The time recorded under key, 1 when there is none, as Aether's TS_UNKNOWN."
  [^java.util.Properties props key]
  (or (some-> (.getProperty props (str key ".lastUpdated")) parse-long) 1))

(defn- update-required?
  "Whether the metadata cached in file is fetched again from repo.
  local-updated is the time of the installed metadata that stands in for
  the repository while it is fresh, 0 for none."
  [file repo policy local-updated]
  (if (and (pos? local-updated) (not (stale? local-updated policy)))
    false
    (let [props (load-properties (read-tracking-file (status-file file)))
          data-key (str (fs/file-name file))
          error (.getProperty props (str data-key ".error"))
          exists (fs/exists? file)
          updated (cond
                    (and (nil? error) (not exists)) 0
                    (seq error) (last-updated props (transfer-key file repo))
                    :else (last-updated props data-key))]
      (or (zero? updated) (stale? updated policy) (not exists)))))

(defn- touch!
  "Records the outcome of a transfer: error is nil for a success, \"\" for
  metadata the repository does not have, else the failure's message."
  [file repo error]
  (let [data-key (str (fs/file-name file))
        transfer-key (transfer-key file repo)
        now (str (System/currentTimeMillis))]
    (fs/create-dirs (fs/parent file))
    (update-tracking-file!
     (status-file file)
     (fn [text]
       (let [props (load-properties text)
             out (java.io.ByteArrayOutputStream.)]
         (cond
           (nil? error) (doto props
                          (.remove (str data-key ".error"))
                          (.setProperty (str data-key ".lastUpdated") now)
                          (.remove (str transfer-key ".lastUpdated")))
           (= "" error) (doto props
                          (.setProperty (str data-key ".error") "")
                          (.setProperty (str data-key ".lastUpdated") now)
                          (.remove (str transfer-key ".lastUpdated")))
           :else (doto props
                   (.setProperty (str data-key ".error") error)
                   (.remove (str data-key ".lastUpdated"))
                   (.setProperty (str transfer-key ".lastUpdated") now)))
         (.store props out "NOTE: This is a Maven Resolver internal implementation file, its format can be changed without prior notice.")
         (.toString out "ISO-8859-1"))))))

(defn- cached-text!
  "Returns the metadata text of repo for the directory rel: the cached copy
  while update-required? says so, else fetched and cached. A repository
  without the metadata deletes the cached copy. A failed transfer leaves it
  in use, as Aether does."
  [local-repo {:keys [id url display-url auth proxy headers] :as repo} rel policy local-updated]
  (let [file (fs/file local-repo rel (str "maven-metadata-" id ".xml"))
        cached #(when (fs/exists? file) (slurp file))]
    (if (update-required? file repo policy local-updated)
      (let [[text error]
            (try [(http/fetch (str url rel "/maven-metadata.xml")
                              {:auth auth :proxy proxy :headers headers :repo-id id :repo-url display-url :label (str rel "/maven-metadata.xml")})]
                 (catch Exception e [nil e]))]
        (cond
          error
          (do (touch! file repo (or (not-empty (ex-message error)) (.getSimpleName (class error))))
              (cached))

          (nil? text)
          (do (try (fs/delete-if-exists file)
                   ;; Aether ignores a failed delete
                   (catch Exception _ nil))
              (touch! file repo "")
              nil)

          :else
          (do (fs/create-dirs (fs/parent file))
              ;; a parallel reader never sees a partial file
              (let [tmp (http/temp-file file)]
                (spit tmp text)
                (http/move-into-place! tmp (str file)))
              (touch! file repo nil)
              text)))
      (cached))))

(defn- parsed
  "Returns (parse text), nil for text that does not parse, as Maven skips
  metadata it cannot read."
  [parse text]
  (when text
    (try (parse text)
         (catch Exception _ nil))))

(defn versions
  "All versions of the artifact across the enabled repositories and the
  local repository's maven-metadata-local.xml, in Maven order, with :latest
  and :release from the first repository that names them."
  [local-repo repos artifact]
  (let [rel (coords/artifact-dir artifact)
        installed (let [f (fs/file local-repo rel "maven-metadata-local.xml")]
                    (when (fs/exists? f)
                      (parsed parse-artifact-metadata (slurp f))))
        found (concat (keep (fn [repo]
                              (when (get-in repo [:releases :enabled])
                                (parsed parse-artifact-metadata (cached-text! local-repo repo rel (:releases repo) 0))))
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
      (when-let [{:keys [snapshot] :as versioning} (parsed parse-snapshot-metadata (slurp f))]
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
        installed (fs/file local-repo rel "maven-metadata-local.xml")
        local-updated (if (fs/exists? installed) (.toMillis (fs/last-modified-time installed)) 0)
        infos (reduce (fn [infos repo]
                        (if-let [versioning (and (get-in repo [:snapshots :enabled])
                                                 (parsed parse-snapshot-metadata (cached-text! local-repo repo rel (:snapshots repo) local-updated)))]
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
