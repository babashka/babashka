(ns babashka.impl.mvn.repo
  "Artifact resolution and local repository caching."
  {:no-doc true}
  (:require [babashka.fs :as fs]
            [babashka.impl.mvn.cipher :as cipher]
            [babashka.impl.mvn.coords :as coords]
            [babashka.impl.mvn.env :as env]
            [babashka.impl.mvn.http :as http]
            [babashka.impl.mvn.metadata :as metadata]
            [babashka.impl.mvn.settings :as settings]
            [babashka.impl.mvn.tracking :as tracking]
            [clojure.string :as str]))

(def standard-repos
  {"central" {:url "https://repo1.maven.org/maven2/"}
   "clojars" {:url "https://repo.clojars.org/"}})

(defn- policy
  ":enabled default true, :update :daily :always :never or minutes,
  :checksum :warn :fail :ignore."
  [name {:keys [enabled update checksum]
         :or {enabled true, update :daily, checksum :warn}}]
  (when-not (#{:warn :fail :ignore} checksum)
    (throw (ex-info (format "Invalid checksum policy: %s on repository: %s" checksum name)
                    {:name name :enabled enabled :update update :checksum checksum})))
  {:enabled enabled :update update :checksum checksum})

(defn- with-slash [url]
  (if (str/ends-with? url "/") url (str url "/")))

(defn- proxy-for
  "The proxy for url, its password decrypted the way a server's is."
  [settings url]
  (when-let [p (settings/proxy-for settings url)]
    (cond-> p
      (:password p) (update :password cipher/decrypt-password {:server (str "proxy " (:host p))}))))

(defn- check-http!
  "Throws for an http: :mvn/repos entry unless CLOJURE_CLI_ALLOW_HTTP_REPO is
  set."
  [[_ {:keys [url] :as config}]]
  (when (and (str/starts-with? url "http:") (nil? (env/getenv "CLOJURE_CLI_ALLOW_HTTP_REPO")))
    (throw (ex-info (str "Invalid repo url (http not supported): " url) (or config {})))))

(defn- caller-server
  "The entry of servers for repo, or nil if none names both its id and its
  URL. A caller gives credentials for one host, so an entry is never used for
  another URL under the same id."
  [servers repo]
  (when-let [server (get servers (:id repo))]
    (when-let [url (:url server)]
      (when (= (with-slash url) (:url repo))
        server))))

(defn remote-repo
  "One repository map from a :mvn/repos entry, with the mirror, auth and
  proxy from settings applied."
  [{:keys [mirrors servers caller-servers] :as settings} [name {:keys [url snapshots releases]}]]
  (let [repo {:id name :url (with-slash url) :display-url url}
        mirror (settings/mirror-for mirrors repo)
        repo (if mirror
               {:id (:id mirror) :url (with-slash (:url mirror)) :display-url (:url mirror) :mirrored [name]}
               repo)
        from-settings (get servers (:id repo))
        ;; settings.xml is the user's own configuration and wins
        {:keys [username password private-key passphrase headers]}
        (or from-settings (caller-server caller-servers repo))
        ;; only settings.xml holds encrypted passwords
        decrypt #(if from-settings (cipher/decrypt-password % {:server (:id repo)}) %)
        credentials (cond-> {}
                      username (assoc :username username)
                      password (assoc :password (decrypt password))
                      private-key (assoc :private-key private-key)
                      (and private-key passphrase) (assoc :passphrase (decrypt passphrase)))]
    ;; Check the repository URL after applying mirrors.
    (when (str/starts-with? (:url repo) "s3:")
      (throw (ex-info (str "S3 repository " (:id repo) " (" (:url repo) ") requires the JVM resolver."
                           " Set BABASHKA_DEPS_RESOLVER=jvm.")
                      {:repo (:id repo) :url (:url repo)})))
    (cond-> (assoc repo
                   :releases (policy name (or releases {}))
                   :snapshots (policy name (or snapshots {})))
      username (assoc :auth [username (:password credentials)])
      (seq credentials) (assoc :credentials credentials)
      (seq headers) (assoc :headers headers)
      (proxy-for settings (:url repo)) (assoc :proxy (proxy-for settings (:url repo))))))

(defn remote-repos
  "Ordered repositories: central, clojars, then the rest, then the
  repositories from active settings profiles."
  [{:strs [central clojars] :as repos} settings]
  (let [entries (concat [["central" central] ["clojars" clojars]]
                        (dissoc repos "central" "clojars")
                        (map (fn [{:keys [id url]}] [id {:url url}])
                             (settings/active-profile-repositories settings)))]
    (reduce (fn [repos repo]
              (if-let [i (first (keep-indexed #(when (= (:id %2) (:id repo)) %1) repos))]
                (let [mirrored (:mirrored (nth repos i))
                      added (remove (set mirrored) (:mirrored repo))]
                  (if (and (seq mirrored) (seq added))
                    (-> repos
                        (update-in [i :releases] metadata/merge-policy (:releases repo))
                        (update-in [i :snapshots] metadata/merge-policy (:snapshots repo))
                        (update-in [i :mirrored] into added))
                    repos))
                (conj repos repo)))
            []
            (into []
                  (comp (remove (fn [[_ config]] (nil? config)))
                        (map (fn [entry]
                               (check-http! entry)
                               (remote-repo settings entry))))
                  entries))))

(defn user-local-repo
  "Returns .m2/repository under the current user.home."
  []
  (str (fs/path (System/getProperty "user.home") ".m2" "repository")))

(def default-local-repo (user-local-repo))

(defn local-repo
  "Returns :mvn/local-repo, else .m2/repository under the current user.home.
  Ignores localRepository in settings.xml, as the JVM tools.deps does."
  [{:keys [mvn/local-repo]}]
  (or local-repo (user-local-repo)))

(defn- record-remote!
  "Notes in _remote.repositories which repository a file came from, the way
  Aether does, so the JVM tools.deps accepts the file later."
  [dir file-name repo-id]
  (let [line (str file-name ">" repo-id "=")]
    (tracking/update-tracking-file! (fs/file dir "_remote.repositories")
                           (fn [existing]
                             (if (str/includes? existing line)
                               existing
                               (str existing line "\n"))))))

(defn- tracked-ids
  "The repository ids _remote.repositories lists for file-name, \"\" for a
  locally installed file. nil when the file is not listed."
  [dir file-name]
  (when-let [text (tracking/read-tracking-file (fs/file dir "_remote.repositories"))]
    (let [props (java.util.Properties.)
          prefix (str file-name ">")]
      (.load props (java.io.StringReader. text))
      (not-empty (into #{} (keep #(when (str/starts-with? % prefix) (subs % (count prefix)))) (keys props))))))

(defn- cached-available?
  "Whether a cached file counts for repos, as Aether decides: no repos at
  all, not listed, installed locally, or listed for one of repos."
  [dir file-name repos]
  (let [ids (tracked-ids dir file-name)]
    (or (empty? repos)
        (nil? ids)
        (contains? ids "")
        (boolean (some #(contains? ids (:id %)) repos)))))

(defn- download!
  "Downloads the file named remote-name in the artifact's directory of repo
  to dest. Returns dest, nil when the repository does not have it."
  [repo artifact remote-name dest policy]
  (let [rel (str (coords/version-dir artifact) "/" remote-name)]
    (when (http/download! (str (:url repo) rel) dest
                          {:auth (:auth repo)
                           :proxy (:proxy repo)
                           :headers (:headers repo)
                           :checksum (get-in repo [policy :checksum])
                           :repo-id (:id repo)
                           :repo-url (:display-url repo)
                           :label rel})
      (record-remote! (str (fs/parent dest)) (str (fs/file-name dest)) (:id repo))
      dest)))

(defn- confirm-cached!
  "Returns dest when the cached file counts for repos. Otherwise records the
  first repository enabled for policy that has the file and returns dest,
  or nil when none has it."
  [repos artifact dest policy]
  (let [dir (str (fs/parent dest))
        file-name (str (fs/file-name dest))
        rel (str (coords/version-dir artifact) "/" file-name)]
    (if (cached-available? dir file-name repos)
      dest
      ;; Aether's existence check: the cached file stays, the repository is recorded
      (some (fn [repo]
              (when (and (get-in repo [policy :enabled])
                         (http/exists? (str (:url repo) rel)
                                       {:auth (:auth repo) :proxy (:proxy repo) :headers (:headers repo)
                                        :repo-id (:id repo) :repo-url (:display-url repo) :label rel}))
                (record-remote! dir file-name (:id repo))
                dest))
            repos))))

(defn- resolve-remote!
  "Returns dest: the cached file when it counts for repos, else downloaded
  from the first repository enabled for policy that has it. nil when none
  has it."
  [repos artifact dest policy]
  (if (fs/exists? dest)
    (confirm-cached! repos artifact dest policy)
    (let [file-name (str (fs/file-name dest))]
      (some (fn [repo]
              (when (get-in repo [policy :enabled])
                (download! repo artifact file-name dest policy)))
            repos))))

(defn- copy-build!
  "Copies the build file over dest when their length or last-modified time
  differ and gives dest the build's time, as Aether's snapshot
  normalization does. Returns dest."
  [build dest]
  (let [b (fs/file build)
        d (fs/file dest)]
    (when (or (not= (.length b) (.length d))
              (not= (.lastModified b) (.lastModified d)))
      (let [tmp (http/temp-file dest)]
        (fs/copy build tmp {:replace-existing true})
        (http/move-into-place! tmp dest)
        (fs/set-last-modified-time dest (fs/last-modified-time build))))
    dest))

(defn- resolve-build!
  "Returns dest holding the timestamped build version, kept under its own
  name, cached or downloaded from repos, or nil when unavailable."
  [repos artifact version dest]
  (let [build (str (fs/file (fs/parent dest) (coords/file-name (assoc artifact :version version))))]
    (when (resolve-remote! repos artifact build :snapshots)
      (copy-build! build dest))))

(defn- resolve-snapshot!
  "Returns dest holding the build metadata/resolve-snapshot picks, or nil
  when unavailable."
  [local-repo repos artifact dest]
  (let [{:keys [version repo]} (metadata/resolve-snapshot local-repo repos artifact)]
    (cond
      (nil? repo) (when (fs/exists? dest) dest)
      (= :none repo) (resolve-remote! repos artifact dest :snapshots)
      :else (resolve-build! [repo] artifact version dest))))

(defn resolve-file!
  "The artifact's file in the local repository, downloaded from the first
  repository that has it. nil when none does. A cached file from a
  repository outside repos is used once one of repos has it too. A -SNAPSHOT
  version follows the newest metadata, including the local repository's. A
  timestamped build resolves as named."
  [local-repo repos {:keys [version] :as artifact}]
  (let [dest (str (fs/path local-repo (coords/local-relative-path artifact)))]
    #_{:clj-kondo/ignore [:locking-suspicious-lock]}
    (locking (tracking/lock-for dest)
      (cond
        (str/ends-with? version "-SNAPSHOT") (resolve-snapshot! local-repo repos artifact dest)
        (coords/snapshot? version) (resolve-build! repos artifact version dest)
        :else (resolve-remote! repos artifact dest :releases)))))
