(ns ^{:skip-wiki true}
  clojure.tools.build.tasks.install
  "BB-STAND-IN for tools.build's install task, which installs through
  Maven Resolver. This one lays the jar and the POM into the local
  repository the way Resolver does: the files under
  group/artifact/version, a _remote.repositories marking them local, and
  the version added to maven-metadata-local.xml. Same params as upstream:
  :basis :lib :classifier :version :jar-file :class-dir."
  {:no-doc true}
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.tools.build.api :as api]
            [clojure.tools.deps.util.maven :as mvn]))

(defn- stamp []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss")
           (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC)))

(defn- record-local!
  "Marks file-names as installed locally in dir's _remote.repositories, the
  entry Resolver reads before it trusts a file it did not download."
  [dir file-names]
  (let [marker (fs/file dir "_remote.repositories")
        lines (if (fs/exists? marker) (str/split-lines (slurp marker)) [])
        lines (remove (fn [l] (some #(str/starts-with? l (str % ">")) file-names)) lines)
        lines (concat (if (seq lines)
                        lines
                        ["#NOTE: This is a Maven Resolver internal implementation file, its format can be changed without prior notice."
                         (str "#" (java.util.Date.))])
                      (map #(str % ">=") file-names))]
    (spit marker (str (str/join "\n" lines) "\n"))))

(defn- add-version!
  "Adds version to the artifact's maven-metadata-local.xml, creating it."
  [artifact-dir group-id artifact-id version]
  (let [f (fs/file artifact-dir "maven-metadata-local.xml")
        existing (when (fs/exists? f)
                   (map second (re-seq #"<version>([^<]+)</version>" (slurp f))))
        versions (distinct (concat existing [version]))
        release (last (remove #(str/ends-with? % "-SNAPSHOT") versions))]
    (spit f (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                 "<metadata>\n"
                 "  <groupId>" group-id "</groupId>\n"
                 "  <artifactId>" artifact-id "</artifactId>\n"
                 "  <versioning>\n"
                 (when release (str "    <release>" release "</release>\n"))
                 "    <versions>\n"
                 (apply str (map #(str "      <version>" % "</version>\n") versions))
                 "    </versions>\n"
                 "    <lastUpdated>" (stamp) "</lastUpdated>\n"
                 "  </versioning>\n"
                 "</metadata>\n"))))

(defn install
  [{:keys [basis lib classifier version jar-file class-dir] :as _params}]
  (let [{:mvn/keys [local-repo]} basis
        group-id (namespace lib)
        artifact-id (name lib)
        jar (api/resolve-path jar-file)
        pom (fs/file (api/resolve-path class-dir) "META-INF" "maven" group-id artifact-id "pom.xml")
        repo (or local-repo @mvn/cached-local-repo)
        artifact-dir (apply fs/file repo (concat (str/split group-id #"\.") [artifact-id]))
        dir (fs/file artifact-dir version)
        base (str artifact-id "-" version (when classifier (str "-" classifier)))
        jar-name (str base ".jar")
        pom-name (str base ".pom")]
    (fs/create-dirs dir)
    (fs/copy jar (fs/file dir jar-name) {:replace-existing true})
    (when (fs/exists? pom)
      (fs/copy pom (fs/file dir pom-name) {:replace-existing true}))
    (record-local! dir (cond-> [jar-name] (fs/exists? pom) (conj pom-name)))
    (add-version! artifact-dir group-id artifact-id version)
    nil))
