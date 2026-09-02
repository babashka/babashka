(ns babashka.mvn.repo
  "Remote repositories, the local repository, and getting files from one
  into the other."
  (:require [babashka.fs :as fs]
            [babashka.mvn.coords :as coords]
            [babashka.mvn.http :as http]
            [babashka.mvn.settings :as settings]
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

(defn- proxy-for [{:keys [proxies]} url]
  (let [protocol (first (str/split url #":" 2))]
    (when-let [p (first (filter #(and (:active %) (= (:protocol %) protocol)) proxies))]
      {:host (:host p) :port (:port p)})))

(defn remote-repo
  "One repository map from a :mvn/repos entry, with the mirror, auth and
  proxy from settings applied."
  [{:keys [mirrors servers] :as settings} [name {:keys [url snapshots releases] :as config}]]
  (when (and (str/starts-with? url "http:") (nil? (System/getenv "CLOJURE_CLI_ALLOW_HTTP_REPO")))
    (throw (ex-info (str "Invalid repo url (http not supported): " url) (or config {}))))
  (let [repo {:id name :url (with-slash url)}
        mirror (settings/mirror-for mirrors repo)
        repo (if mirror
               {:id (:id mirror) :url (with-slash (:url mirror))}
               repo)
        {:keys [username password]} (get servers (:id repo))]
    (cond-> (assoc repo
                   :releases (policy name (or releases {}))
                   :snapshots (policy name (or snapshots {})))
      username (assoc :auth [username password])
      (proxy-for settings (:url repo)) (assoc :proxy (proxy-for settings (:url repo))))))

(defn remote-repos
  "Ordered repositories: central, clojars, then the rest, then the
  repositories from active settings profiles."
  [{:strs [central clojars] :as repos} settings]
  (let [entries (concat [["central" central] ["clojars" clojars]]
                        (dissoc repos "central" "clojars")
                        (map (fn [{:keys [id url]}] [id {:url url}])
                             (settings/active-profile-repositories settings)))]
    (into []
          (comp (remove (fn [[_ config]] (nil? config)))
                (map #(remote-repo settings %)))
          entries)))

(def default-local-repo
  (str (fs/path (System/getProperty "user.home") ".m2" "repository")))

(defn local-repo
  "The local repository: :mvn/local-repo, then settings, then ~/.m2."
  [{:keys [mvn/local-repo]} settings]
  (or local-repo (:local-repository settings) default-local-repo))

(defn- record-remote!
  "Notes in _remote.repositories which repository a file came from, the way
  Aether does, so the JVM tools.deps accepts the file later."
  [dir file-name repo-id]
  (let [marker (fs/file dir "_remote.repositories")
        line (str file-name ">" repo-id "=")
        existing (if (fs/exists? marker) (slurp marker) "")]
    (when-not (str/includes? existing line)
      (spit marker (str existing line "\n")))))

(defn resolve-file!
  "The artifact's file in the local repository, downloaded from the first
  repository that has it. nil when none does. A timestamped snapshot
  version is a plain file, a -SNAPSHOT version needs the metadata, which is
  not there yet."
  [local-repo repos {:keys [version] :as artifact}]
  (when (str/ends-with? version "-SNAPSHOT")
    (throw (ex-info (str "SNAPSHOT versions are not supported yet: " (coords/file-name artifact))
                    {:artifact artifact})))
  (let [rel (coords/relative-path artifact)
        dest (str (fs/path local-repo rel))
        policy (if (coords/snapshot? version) :snapshots :releases)]
    (if (fs/exists? dest)
      dest
      (loop [[repo & more] repos]
        (when repo
          (if (and (get-in repo [policy :enabled])
                   (http/download! (str (:url repo) rel) dest
                                   {:auth (:auth repo)
                                    :proxy (:proxy repo)
                                    :checksum (get-in repo [policy :checksum])
                                    :repo-id (:id repo)
                                    :label rel}))
            (do (record-remote! (fs/parent dest) (coords/file-name artifact) (:id repo))
                dest)
            (recur more)))))))

(defn fetch-text!
  "A repository file as a string, from the first repository that has it,
  without caching. nil when none does."
  [repos rel]
  (loop [[repo & more] repos]
    (when repo
      (or (when (get-in repo [:releases :enabled])
            (http/fetch (str (:url repo) rel) {:auth (:auth repo) :proxy (:proxy repo)}))
          (recur more)))))
