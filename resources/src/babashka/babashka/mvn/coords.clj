(ns babashka.mvn.coords
  "Maven coordinates and the repository layout."
  (:require [clojure.string :as str]))

(defn lib->names
  "Split lib symbol into [group-id artifact-id classifier]."
  [lib]
  (let [[artifact-id classifier] (str/split (name lib) #"\$")]
    [(or (namespace lib) artifact-id) artifact-id classifier]))

;; Maven packaging type to [extension classifier].
(def ^:private types
  {"jar" ["jar" nil]
   "pom" ["pom" nil]
   "bundle" ["jar" nil]
   "maven-plugin" ["jar" nil]
   "ejb" ["jar" nil]
   "ejb-client" ["jar" "client"]
   "test-jar" ["jar" "tests"]
   "javadoc" ["jar" "javadoc"]
   "java-source" ["jar" "sources"]})

(defn type->extension [type]
  (or (first (get types type)) type))

(defn type->classifier [type]
  (second (get types type)))

(defn artifact
  "The artifact map for a lib and a tools.deps coordinate."
  [lib {:keys [mvn/version extension classifier] :or {extension "jar"} :as coord}]
  (when classifier
    (throw (ex-info (str "Invalid library spec:\n"
                         (format "  %s %s\n" lib (dissoc coord :deps/manifest))
                         ":classifier in Maven coordinates is no longer supported.\n"
                         "Use groupId/artifactId$classifier in lib names instead.")
                    {:lib lib, :coord coord})))
  (let [[group-id artifact-id classifier] (lib->names lib)]
    (cond-> {:group group-id
             :artifact artifact-id
             :version version
             :extension extension}
      classifier (assoc :classifier classifier))))

(defn file-name
  "The file name in a remote repository, timestamped for a snapshot."
  [{:keys [artifact version classifier extension]}]
  (str artifact "-" version (when classifier (str "-" classifier)) "." extension))

(defn artifact-dir
  "Path of the artifact directory, relative to a repository root."
  [{:keys [group artifact]}]
  (str (str/replace group "." "/") "/" artifact))

(defn base-version
  "A timestamped snapshot version, 1.0-20240101.123456-3, lives in the
  1.0-SNAPSHOT directory. Other versions are their own base."
  [version]
  (str/replace version #"-\d{8}\.\d{6}-\d+$" "-SNAPSHOT"))

(defn snapshot? [version]
  (str/ends-with? (base-version version) "-SNAPSHOT"))

(defn version-dir
  "Path of the version directory, relative to a repository root."
  [{:keys [version] :as a}]
  (str (artifact-dir a) "/" (base-version version)))

(defn local-file-name
  "The file name in the local repository. Aether keeps a timestamped
  snapshot under its base version."
  [a]
  (file-name (update a :version base-version)))

(defn relative-path
  "Path of the artifact file in a remote repository."
  [a]
  (str (version-dir a) "/" (file-name a)))

(defn local-relative-path
  "Path of the artifact file in the local repository."
  [a]
  (str (version-dir a) "/" (local-file-name a)))

(defn version-range? [version]
  (boolean (when version (re-find #"\[|\(" version))))
