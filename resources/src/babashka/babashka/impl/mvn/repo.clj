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
            [clojure.java.io :as io]
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

(defn remote-repo
  "One repository map from a :mvn/repos entry, with the mirror, auth and
  proxy from settings applied."
  [{:keys [mirrors servers] :as settings} [name {:keys [url snapshots releases]}]]
  (let [repo {:id name :url (with-slash url)}
        mirror (settings/mirror-for mirrors repo)
        repo (if mirror
               {:id (:id mirror) :url (with-slash (:url mirror))}
               repo)
        {:keys [username password headers]} (get servers (:id repo))]
    ;; Check the repository URL after applying mirrors.
    (when (str/starts-with? (:url repo) "s3:")
      (throw (ex-info (str "S3 repository " (:id repo) " (" (:url repo) ") requires the JVM resolver."
                           " Set BABASHKA_DEPS_RESOLVER=jvm.")
                      {:repo (:id repo) :url (:url repo)})))
    (cond-> (assoc repo
                   :releases (policy name (or releases {}))
                   :snapshots (policy name (or snapshots {})))
      username (assoc :auth [username (cipher/decrypt-password password {:server (:id repo)})])
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
    ;; Two repositories behind one mirror are one repository, as Aether
    ;; merges them, so the second is dropped.
    (reduce (fn [repos repo]
              (if (some #(= (:id %) (:id repo)) repos)
                repos
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
  (let [marker (fs/file dir "_remote.repositories")
        line (str file-name ">" repo-id "=")
        existing (if (fs/exists? marker) (slurp marker) "")]
    (when-not (str/includes? existing line)
      (spit marker (str existing line "\n")))))

(defn- tracked-ids
  "The repository ids _remote.repositories lists for file-name, \"\" for a
  locally installed file. nil when the file is not listed."
  [dir file-name]
  (let [marker (fs/file dir "_remote.repositories")]
    (when (fs/exists? marker)
      (let [props (java.util.Properties.)
            prefix (str file-name ">")]
        (with-open [r (io/reader marker)]
          (.load props r))
        (not-empty (into #{} (keep #(when (str/starts-with? % prefix) (subs % (count prefix)))) (keys props)))))))

(defn- cached-available?
  "Whether a cached file counts for repos, as Aether decides: no repos at
  all, not listed, installed locally, or listed for one of repos."
  [dir file-name repos]
  (let [ids (tracked-ids dir file-name)]
    (or (empty? repos)
        (nil? ids)
        (contains? ids "")
        (boolean (some #(contains? ids (:id %)) repos)))))

;; tools.deps resolves in parallel, and parents and BOMs are shared, so two
;; threads can want the same file. One monitor per path.
(def ^:private locks (atom {}))

(defn- lock-for [path]
  (or (get @locks path)
      (get (swap! locks update path #(or % (Object.))) path)))

(defn- remote-file-name
  "The file's name in repo. A -SNAPSHOT version names its timestamped file
  through the repository's metadata, nil when the repository has none."
  [local-repo repo {:keys [version] :as artifact}]
  (if (str/ends-with? version "-SNAPSHOT")
    (metadata/snapshot-file-name local-repo repo artifact)
    (coords/file-name artifact)))

;; A snapshot's local file carries the base version, so _babashka.snapshots
;; in the version directory records which remote file it holds.

(defn- snapshots-file [dir]
  (fs/file dir "_babashka.snapshots"))

(defn- recorded-snapshot [dir local-name]
  (let [f (snapshots-file dir)]
    (when (fs/exists? f)
      (some (fn [line]
              (let [[l r] (str/split line #">" 2)]
                (when (= l local-name) r)))
            (str/split-lines (slurp f))))))

(defn- record-snapshot! [dir local-name remote-name]
  (let [f (snapshots-file dir)
        lines (if (fs/exists? f) (str/split-lines (slurp f)) [])
        lines (remove #(str/starts-with? % (str local-name ">")) lines)]
    (spit f (str (str/join "\n" (conj (vec lines) (str local-name ">" remote-name))) "\n"))))

(defn- download-from!
  "Returns dest if cached or downloaded from repo, or nil if unavailable."
  [local-repo repo artifact dest policy]
  (let [local-name (coords/local-file-name artifact)
        dir (fs/parent dest)
        snapshot? (coords/snapshot? (:version artifact))
        remote-name (remote-file-name local-repo repo artifact)]
    (when remote-name
      (if (and (fs/exists? dest)
               (or (not snapshot?)
                   (= remote-name (recorded-snapshot dir local-name))))
        dest
        (let [rel (str (coords/version-dir artifact) "/" remote-name)]
          (when (http/download! (str (:url repo) rel) dest
                                {:auth (:auth repo)
                                 :proxy (:proxy repo)
                                 :headers (:headers repo)
                                 :checksum (get-in repo [policy :checksum])
                                 :repo-id (:id repo)
                                 :label rel})
            (record-remote! dir local-name (:id repo))
            (when snapshot? (record-snapshot! dir local-name remote-name))
            dest))))))

(defn resolve-file!
  "The artifact's file in the local repository, downloaded from the first
  repository that has it. nil when none does. A cached file from a
  repository outside repos is used once one of repos has it too. A snapshot
  is refreshed when the repository's metadata names a newer build."
  [local-repo repos {:keys [version] :as artifact}]
  (let [dest (str (fs/path local-repo (coords/local-relative-path artifact)))
        dir (str (fs/parent dest))
        file-name (str (fs/file-name dest))
        rel (coords/relative-path artifact)
        policy (if (coords/snapshot? version) :snapshots :releases)]
    #_{:clj-kondo/ignore [:locking-suspicious-lock]}
    (locking (lock-for dest)
      (if (and (fs/exists? dest) (not (coords/snapshot? version)))
        (if (cached-available? dir file-name repos)
          dest
          ;; Aether's existence check: the cached file stays, the repository is recorded
          (some (fn [repo]
                  (when (and (get-in repo [policy :enabled])
                             (http/exists? (str (:url repo) rel)
                                           {:auth (:auth repo) :proxy (:proxy repo) :headers (:headers repo)
                                            :repo-id (:id repo) :label rel}))
                    (record-remote! dir file-name (:id repo))
                    dest))
                repos))
        (loop [[repo & more] repos]
          (when repo
            (or (when (get-in repo [policy :enabled])
                  (download-from! local-repo repo artifact dest policy))
                (recur more))))))))
