;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.tools.deps.util.maven
  "Low-level access to Maven via the Mima (Mini Maven) and maven-resolver libs.
  IMPL NAMESPACE - use at your own risk, subject to change.
  See clojure.tools.deps.extensions.maven for canonical examples of use."
  (:require
    [clojure.java.io :as jio]
    [clojure.string :as str]
    [clojure.tools.deps.util.io :refer [printerrln]])
  (:import
    [java.nio.file Paths]

    ;; MIMA
    [eu.maveniverse.maven.mima.context Context ContextOverrides Runtimes]
    [eu.maveniverse.maven.mima.runtime.standalonestatic StandaloneStaticRuntime]

    ;; maven-resolver-api
    [org.eclipse.aether RepositorySystem RepositorySystemSession]
    [org.eclipse.aether.artifact Artifact DefaultArtifact]
    [org.eclipse.aether.repository LocalRepository RemoteRepository RemoteRepository$Builder RepositoryPolicy]
    [org.eclipse.aether.graph Dependency Exclusion]
    [org.eclipse.aether.transfer TransferListener TransferEvent]

    ;; maven-settings
    [org.apache.maven.settings Settings]

    ;; maven-settings-builder
    [org.apache.maven.settings.building SettingsBuilder DefaultSettingsBuilderFactory DefaultSettingsBuildingRequest]))

(set! *warn-on-reflection* true)

;; Remote repositories

(def standard-repos {"central" {:url "https://repo1.maven.org/maven2/"}
                     "clojars" {:url "https://repo.clojars.org/"}})

(defn get-settings
  ^Settings []
  (let [^SettingsBuilder builder (.newInstance (DefaultSettingsBuilderFactory.))
        request (DefaultSettingsBuildingRequest.)
        user-settings (jio/file (System/getProperty "user.home") ".m2" "settings.xml")]
    (when (.exists user-settings)
      (.setUserSettingsFile request user-settings))
    (.getEffectiveSettings (.build builder request))))

(defn- repo-policy
  "Converts repo policy map to RepositoryPolicy.
   :enabled - is enabled (default = true)
   :update - one of :daily (default), :always, :never, or an interval in minutes
   :checksum - one of :warn (default), :fail, :ignore"
  [name {:keys [enabled update checksum]
         :or {enabled true, update :daily, checksum :warn}}]
  (RepositoryPolicy. enabled
    (case update
      :daily RepositoryPolicy/UPDATE_POLICY_DAILY
      :always RepositoryPolicy/UPDATE_POLICY_ALWAYS
      :never RepositoryPolicy/UPDATE_POLICY_NEVER
      (str update))
    (case checksum
      :warn RepositoryPolicy/CHECKSUM_POLICY_WARN
      :fail RepositoryPolicy/CHECKSUM_POLICY_FAIL
      :ignore RepositoryPolicy/CHECKSUM_POLICY_IGNORE
      (throw (ex-info (format "Invalid checksum policy: %s on repository: %s" checksum name)
               {:name name
                :enabled enabled
                :update update
                :checksum checksum})))))

(defn remote-repo
  "Use 1-arity, settings no longer needed/used"
  (^RemoteRepository [[^String name {:keys [url snapshots releases] :as repo-config}]]
   (when (and (str/starts-with? url "http:") (nil? (System/getenv "CLOJURE_CLI_ALLOW_HTTP_REPO")))
     (throw (ex-info (str "Invalid repo url (http not supported): " url) (or repo-config {}))))
   (let [builder (RemoteRepository$Builder. name "default" url)]
     (cond-> builder
       snapshots (.setSnapshotPolicy (repo-policy name snapshots))
       releases (.setReleasePolicy (repo-policy name releases)))
     (.build builder)))
  (^RemoteRepository [repo-entry _settings]
   ;; settings is no longer needed, the MIMA context does mirror/proxy/auth later.
   ;; this arity is deprecated and here for backwards compatibility only.
   (remote-repo repo-entry)))

(defn remote-repos
  "Arities:
    1 - will not resolve mirros, proxies, server auth!
    2 - deprecated, same as 1 (and settings ignored)
    3 - PREFERRED - pass system and session to resolve repos"
  ([{:strs [central clojars] :as repos}]
   ;; always return central, then clojars, then other repos
   (->> (concat [["central" central] ["clojars" clojars]] (dissoc repos "central" "clojars"))
     (remove (fn [[_name config]] (nil? config)))
     (mapv remote-repo)))
  ([repos _settings]
   ;; deprecated, settings not used
   (remote-repos repos))
  ([^RepositorySystem system ^RepositorySystemSession session repos]
   ;; resolve against mirrors, proxies, auth
   (.newResolutionRepositories system session (remote-repos repos))))

;; Local repository

(defn ^:private local-repo-path
  "Helper to form the path to the default local repo - use `@cached-local-repo` for
  caching delayed value"
  []
  (.getAbsolutePath (jio/file (System/getProperty "user.home") ".m2" "repository")))

(def default-local-repo
  "DEPRECATED - use `@cached-local-repo`"
  (local-repo-path))

(def cached-local-repo
  "Delayed default local repo lookup for ~/.m2/repository, access with `@cached-local-repo`"
  (delay (local-repo-path)))

(defn make-local-repo
  ^LocalRepository [^String dir]
  (LocalRepository. dir))

;; Transfer listener

(def ^TransferListener console-listener
  (reify TransferListener
    (transferStarted [_ event]
      (let [event ^TransferEvent event
            resource (.getResource event)
            name (.getResourceName resource)
            repo (.getRepositoryId resource)]
        (printerrln "Downloading:" name "from" repo)))
    (transferCorrupted [_ event]
      (printerrln "Download corrupted:" (.. ^TransferEvent event getException getMessage)))
    (transferFailed [_ event]
      ;; This happens when Maven can't find an artifact in a particular repo
      ;; (but still may find it in a different repo), ie this is a common event
      #_(printerrln "Download failed:" (.. ^TransferEvent event getException getMessage)))
    (transferInitiated [_ _event])
    (transferProgressed [_ _event])
    (transferSucceeded [_ _event])))

;; MIMA runtime.
;;
;; The default StandaloneStaticRuntime ships only file + http transporters.
;; The `org.clojure/tools.deps.maven-s3-transporter` artifact (if
;; present on the classpath) registers a higher-precedence Runtime via SPI that
;; also wires an `s3` transporter - this is done via a Java ServiceLoader.
;;
;; The s3 transporter uses cognitect.aws/api for s3 access, and that default
;; http-client requires java.net.http.HttpClient (added in Java 11). So, the s3
;; transporter runtime is only loaded when its present.

(defn- http-client-available?
  "Returns true if java.net.http.HttpClient is on the classpath."
  []
  (try
    (Class/forName "java.net.http.HttpClient")
    true
    (catch ClassNotFoundException _ false)))

(def ^:private the-runtime
  (delay
    (if (http-client-available?)
      (.getRuntime Runtimes/INSTANCE)
      (StandaloneStaticRuntime.))))

;; MIMA context and session

(defn make-context
  "Build a MIMA Context. The Context bundles a RepositorySystem, a
   RepositorySystemSession (with mirror/proxy/auth selectors configured from
   settings.xml or the supplied Settings), and a Lookup for resolver components."
  (^Context [& {:keys [settings local-repo]}]
   (when-not (System/getProperty "aether.connector.userAgent")
     (System/setProperty "aether.connector.userAgent" "tools.deps"))
   (.create ^eu.maveniverse.maven.mima.context.Runtime @the-runtime
     (.build
       (cond-> (-> (ContextOverrides/create)
                 (.withUserSettings true)
                 (.transferListener console-listener))
         settings (.withEffectiveSettings settings)
         local-repo (.withLocalRepositoryOverride
                      (Paths/get local-repo (into-array String []))))))))

(defn make-system
  "0-arity is deprecated, use: (make-system context)"
  (^RepositorySystem []
   (make-system (make-context)))
  (^RepositorySystem [^Context context]
   (.repositorySystem context)))

(defn make-system-session
  ^RepositorySystemSession [^Context context]
  (.repositorySystemSession context))

(defn make-session
  "DEPRECATED: use make-system-session"
  {:deprecated "1.0"}
  (^RepositorySystemSession [_system local-repo] ;; DEPRECATED
   (make-session nil nil local-repo))
  (^RepositorySystemSession [_system settings local-repo]
   (make-system-session (make-context :local-repo local-repo :settings settings))))

(defn exclusions->data
  [exclusions]
  (when (and exclusions (pos? (count exclusions)))
    (into #{}
      (map (fn [^Exclusion exclusion]
             (symbol (.getGroupId exclusion) (.getArtifactId exclusion))))
      exclusions)))

(defn dep->data
  [^Dependency dep]
  (let [scope (.getScope dep)
        optional (.isOptional dep)
        exclusions (exclusions->data (.getExclusions dep))
        ^Artifact artifact (.getArtifact dep)
        artifact-id (.getArtifactId artifact)
        classifier (.getClassifier artifact)
        ext (.getExtension artifact)]
    [(symbol (.getGroupId artifact) (if (str/blank? classifier) artifact-id (str artifact-id "$" classifier)))
     (cond-> {:mvn/version (.getVersion artifact)}
       (not= "jar" ext) (assoc :extension ext)
       scope (assoc :scope scope)
       optional (assoc :optional true)
       (seq exclusions) (assoc :exclusions exclusions))]))

(defn lib->names
  "Split lib symbol into [group-id artifact-id classifier]"
  [lib]
  (let [[artifact-id classifier] (str/split (name lib) #"\$")]
    [(or (namespace lib) artifact-id) artifact-id classifier]))

(defn coord->artifact
  ^Artifact [lib {:keys [mvn/version classifier extension] :or {extension "jar"} :as coord}]
  (when classifier
    (throw (ex-info (str "Invalid library spec:\n"
                         (format "  %s %s\n" lib (dissoc coord :deps/manifest))
                         ":classifier in Maven coordinates is no longer supported.\n"
                         "Use groupId/artifactId$classifier in lib names instead.")
                    {:lib lib, :coord coord})))
  (let [[group-id artifact-id classifier] (lib->names lib)
        version (or version "LATEST")
        artifact (DefaultArtifact. group-id artifact-id classifier extension version)]
    artifact))

(defn version-range?
  [version]
  (boolean (when version (re-find #"\[|\(" version))))
