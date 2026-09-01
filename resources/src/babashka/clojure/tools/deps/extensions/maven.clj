;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.tools.deps.extensions.maven
  (:require
    [clojure.java.io :as jio]
    [clojure.string :as str]
    [clojure.tools.deps.extensions :as ext]
    [clojure.tools.deps.util.maven :as maven]
    [clojure.tools.deps.util.session :as session])
  (:import
    [java.io File]

    ;; MIMA
    [eu.maveniverse.maven.mima.context Context]

    ;; maven-resolver-api
    [org.eclipse.aether RepositorySystem RepositorySystemSession]
    [org.eclipse.aether.resolution ArtifactRequest ArtifactDescriptorRequest VersionRangeRequest
                                   VersionRequest ArtifactResolutionException ArtifactDescriptorResult]
    [org.eclipse.aether.version Version]

    ;; maven-resolver-util
    [org.eclipse.aether.util.version GenericVersionScheme]))

(set! *warn-on-reflection* true)

;; Main extension points for using Maven deps

(defmethod ext/coord-type-keys :mvn
  [_type]
  #{:mvn/version})

(defn- specific-version
  [version]
  (second (re-matches #"^\[([^,]*)]$" version)))

(defn- resolve-version-range
  ;; only call when version is a range
  [lib {:keys [mvn/version] :as coord} {:keys [mvn/local-repo mvn/repos] :as config}]
  (let [local-repo (or local-repo @maven/cached-local-repo)
        context ^Context (session/retrieve :mvn/context #(maven/make-context :local-repo local-repo))
        system ^RepositorySystem (session/retrieve :mvn/system #(maven/make-system context))
        session ^RepositorySystemSession (session/retrieve :mvn/session #(maven/make-system-session context))]
    (or
      (session/retrieve {:type :mvn/highest-version lib version}
        (fn []
          (let [artifact (maven/coord->artifact lib coord)
                req (VersionRangeRequest. artifact (maven/remote-repos system session repos) nil)
                result (.resolveVersionRange system session req)
                high-version (and result (.getHighestVersion result))]
            (when high-version (.toString ^Version high-version)))))
      (throw (ex-info (str "Unable to resolve " lib " version: " version) {:lib lib :coord coord})))))

(defmethod ext/canonicalize :mvn
  [lib {:keys [mvn/version] :as coord} {:keys [mvn/repos mvn/local-repo] :as config}]
  (let [specific (specific-version version)]
    (cond
      (contains? #{"RELEASE" "LATEST"} version)
      (let [local-repo (or local-repo @maven/cached-local-repo)
            context ^Context (session/retrieve :mvn/context #(maven/make-context :local-repo local-repo))
            system ^RepositorySystem (session/retrieve :mvn/system #(maven/make-system context))
            session ^RepositorySystemSession (session/retrieve :mvn/session #(maven/make-system-session context))
            artifact (maven/coord->artifact lib coord)
            req (VersionRequest. artifact (maven/remote-repos system session repos) nil)
            result (.resolveVersion system session req)]
        (if result
          [lib (assoc coord :mvn/version (.getVersion result))]
          (throw (ex-info (str "Unable to resolve " lib " version: " version) {:lib lib :coord coord}))))

      ;; cuts down on version range requests when we're not going to honor it anyways
      specific
      [lib (assoc coord :mvn/version specific)]

      (maven/version-range? version)
      [lib (assoc coord :mvn/version (resolve-version-range lib coord config))]

      :else
      [lib coord])))

(defmethod ext/lib-location :mvn
  [lib {:keys [mvn/version]} {:keys [mvn/local-repo]}]
  (let [[group-id artifact-id classifier] (maven/lib->names lib)]
    {:base (or local-repo @maven/cached-local-repo)
     :path (.getPath ^File
             (apply jio/file
               (concat (str/split group-id #"\.") [artifact-id version])))
     :classifier classifier
     :type :mvn}))

(defmethod ext/dep-id :mvn
  [_lib coord _config]
  (select-keys coord [:mvn/version]))

(defmethod ext/manifest-type :mvn
  [_lib _coord _config]
  {:deps/manifest :mvn})

(defmethod ext/coord-summary :mvn [lib {:keys [mvn/version]}]
  (str lib " " version))

(defn- read-descriptor
  ^ArtifactDescriptorResult [lib coord {:keys [mvn/repos mvn/local-repo]}]
  (let [local-repo (or local-repo @maven/cached-local-repo)
        context ^Context (session/retrieve :mvn/context #(maven/make-context :local-repo local-repo))
        system ^RepositorySystem (session/retrieve :mvn/system #(maven/make-system context))
        session ^RepositorySystemSession (session/retrieve :mvn/session #(maven/make-system-session context))
        artifact (maven/coord->artifact lib coord)
        repos (maven/remote-repos system session repos)
        req (ArtifactDescriptorRequest. artifact repos nil)]
    (.readArtifactDescriptor system session req)))

(defn- check-version
  [lib {:keys [mvn/version]}]
  (when (nil? version)
    (throw (ex-info (str "No :mvn/version specified for " lib) {}))))

(defmethod ext/license-info :mvn
  [lib coord config]
  (check-version lib coord)
  (let [descriptor (read-descriptor lib coord config)
        props (.getProperties descriptor)
        name (some-> props (.get "license.0.name"))
        url (some-> props (.get "license.0.url"))]
    (when (or name url)
      (cond-> {} name (assoc :name name) url (assoc :url url)))))

(defonce ^:private version-scheme (GenericVersionScheme.))

(defn- parse-version [{version :mvn/version :as _coord}]
  (.parseVersion ^GenericVersionScheme version-scheme ^String version))

(defmethod ext/compare-versions [:mvn :mvn]
  [lib coord-x coord-y _config]
  (check-version lib coord-x)
  (check-version lib coord-y)
  (apply compare (map parse-version [coord-x coord-y])))

(defmethod ext/coord-deps :mvn
  [lib coord _manifest config]
  (check-version lib coord)
  (let [descriptor (read-descriptor lib coord config)]
    (into []
      (comp
        (map maven/dep->data)
        (filter #(contains? #{"compile" "runtime"} (:scope (second %))))
        (remove (comp :optional second))
        (map #(update-in % [1] dissoc :scope :optional)))
      (.getDependencies descriptor))))

(defn- get-artifact
  [lib coord ^RepositorySystem system ^RepositorySystemSession session mvn-repos]
  (check-version lib coord)
  (try
    (let [artifact (maven/coord->artifact lib coord)
          req (ArtifactRequest. artifact mvn-repos nil)
          result (.resolveArtifact system session req)]
      (cond
        (.isResolved result) (.. result getArtifact getFile getAbsolutePath)
        (.isMissing result) (throw (ex-info (str "Unable to download: [" lib (pr-str (:mvn/version coord)) "]") {:lib lib :coord coord}))
        :else (throw (first (.getExceptions result)))))
    (catch ArtifactResolutionException e
      (throw (ex-info (.getMessage e) {:lib lib, :coord coord})))))

(defmethod ext/coord-paths :mvn
  [lib {:keys [extension] :or {extension "jar"} :as coord} _manifest {:keys [mvn/repos mvn/local-repo]}]
  (check-version lib coord)
  (when (contains? #{"jar"} extension)
    (let [local-repo (or local-repo @maven/cached-local-repo)
          context ^Context (session/retrieve :mvn/context #(maven/make-context :local-repo local-repo))
          system ^RepositorySystem (session/retrieve :mvn/system #(maven/make-system context))
          session ^RepositorySystemSession (session/retrieve :mvn/session #(maven/make-system-session context))
          mvn-repos (maven/remote-repos system session repos)]
      [(get-artifact lib coord system session mvn-repos)])))

(defmethod ext/manifest-file :mvn
  [_lib {:keys [deps/root] :as _coord} _mf _config]
  nil)

(defmethod ext/find-versions :mvn
  [lib _coord _coord-type {:keys [mvn/repos mvn/local-repo]}]
  (let [local-repo (or local-repo maven/default-local-repo)
        context ^Context (session/retrieve :mvn/context #(maven/make-context :local-repo local-repo))
        system ^RepositorySystem (session/retrieve :mvn/system #(maven/make-system context))
        session ^RepositorySystemSession (session/retrieve :mvn/session #(maven/make-system-session context))
        artifact (maven/coord->artifact lib {:mvn/version "(,]"})
        req (VersionRangeRequest. artifact (maven/remote-repos system session repos) nil)
        result (.resolveVersionRange system session req)
        versions (.getVersions result)]
    (when (seq versions)
      (->> versions
           (map str)
           (remove #(str/ends-with? % "-SNAPSHOT"))
           (map #(hash-map :mvn/version %))
           (into [])))))

(defmethod ext/coord-usage :mvn
  [lib {:keys [deps/root]} manifest-type config]
  ;; TBD - could look in jar, could download well-known classifier
  nil)

(defmethod ext/prep-command :mvn
  [lib {:keys [deps/root]} manifest-type config]
  ;; TBD - could look in jar, could download well-known classifier
  nil)

(comment
  (ext/lib-location 'org.clojure/clojure {:mvn/version "1.8.0"} {})

  (binding [*print-namespace-maps* false]
    (run! prn
      (ext/find-versions 'org.clojure/clojure nil :mvn {:mvn/repos maven/standard-repos})))

  ;; given a dep, find the child deps
  (ext/coord-deps 'org.clojure/clojure {:mvn/version "1.9.0-alpha17"} :mvn {:mvn/repos maven/standard-repos})

  (ext/coord-deps 'cider/cider-nrepl {:mvn/version "0.17.0-SNAPSHOT"} :mvn {:mvn/repos maven/standard-repos})
  (ext/canonicalize 'joda-time/joda-time {:mvn/version "[2.2,)"} {:mvn/repos maven/standard-repos})

  ;; give a dep, download just that dep (not transitive - that's handled by the core algorithm)
  (ext/coord-paths 'org.clojure/clojure {:mvn/version "1.9.0-alpha17"} :mvn {:mvn/repos maven/standard-repos})

  ;; get specific classifier
  (ext/coord-paths 'org.jogamp.gluegen/gluegen-rt$natives-linux-amd64 {:mvn/version "2.3.2"}
    :mvn {:mvn/repos maven/standard-repos})

  (parse-version {:mvn/version "1.1.0"})

  (ext/compare-versions 'org.clojure/clojure {:mvn/version "1.1.0-alpha10"} {:mvn/version "1.1.0-beta1"} {})

  (ext/coord-deps 'org.clojure/clojure {:mvn/version "1.10.0-master-SNAPSHOT"} :mvn
    {:mvn/repos (merge maven/standard-repos
                  {"sonatype-oss-public" {:url "https://oss.sonatype.org/content/groups/public/"}})})

  (def rr (maven/remote-repo ["sonatype-oss-public" {:url "https://oss.sonatype.org/content/groups/public/"}])))


