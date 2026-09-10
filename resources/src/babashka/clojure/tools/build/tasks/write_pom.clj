;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build.tasks.write-pom
  (:require
    [clojure.java.io :as jio]
    [clojure.string :as str]
    [clojure.data.xml :as xml]
    [clojure.data.xml.tree :as tree]
    [clojure.data.xml.event :as event]
    [clojure.walk :as walk]
    [clojure.zip :as zip]
    [clojure.tools.deps.util.maven :as maven]
    [clojure.tools.deps.util.io :refer [printerrln]]
    [clojure.tools.build.api :as api]
    [clojure.tools.build.util.file :as file])
  (:import [clojure.data.xml.node Element]
           [java.io Reader]
           [java.time Instant ZoneId ZonedDateTime]
           [java.time.format DateTimeFormatter]))

(set! *warn-on-reflection* true)

(xml/alias-uri 'pom "http://maven.apache.org/POM/4.0.0")
(def ^:private pom-ns (name (.-name ^clojure.lang.Namespace (get (ns-aliases *ns*) 'pom))))


(defn- to-dep
  [[lib {:keys [mvn/version exclusions optional] :as coord}]]
  (let [[group-id artifact-id classifier] (maven/lib->names lib)]
    (if version
      (cond->
        [::pom/dependency
         [::pom/groupId group-id]
         [::pom/artifactId artifact-id]
         [::pom/version version]]

        classifier
        (conj [::pom/classifier classifier])

        (seq exclusions)
        (conj [::pom/exclusions
               (map (fn [excl]
                      [::pom/exclusion
                       [::pom/groupId (or (namespace excl) (name excl))]
                       [::pom/artifactId (name excl)]])
                 exclusions)])

        optional
        (conj [::pom/optional "true"]))
      (printerrln "Skipping coordinate:" coord))))

(defn- gen-deps
  [deps]
  [::pom/dependencies
   (map to-dep deps)])

(defn- gen-source-dir
  [path]
  [::pom/sourceDirectory path])

(defn- to-resource
  [resource]
  [::pom/resource
   [::pom/directory resource]])

(defn- gen-resources
  [rpaths]
  [::pom/resources
   (map to-resource rpaths)])

(defn- to-repo-policy
  [parent-tag {:keys [enabled update checksum]}]
  [parent-tag
   (when (some? enabled) [::pom/enabled (str enabled)])
   (when update [::pom/updatePolicy (if (keyword? update) (name update) (str "interval:" update))])
   (when checksum [::pom/checksumPolicy (name checksum)])])

(defn- to-repo
  [[name {:keys [url snapshots releases]}]]
  [::pom/repository
   [::pom/id name]
   [::pom/url url]
   (when releases (to-repo-policy ::pom/releases releases))
   (when snapshots (to-repo-policy ::pom/snapshots snapshots))])

(defn- gen-repos
  [repos]
  [::pom/repositories
   (map to-repo repos)])

(defn- pomify
  [val]
  (if (and (vector? val) (keyword? (first val)))
    (into [(keyword pom-ns (name (first val)))] (rest val))
    val))

(defn- gen-pom
  [{:keys [deps src-paths resource-paths repos group artifact version scm pom-data]
    :or {version "0.1.0"}}]
  (let [[path & paths] src-paths
        {:keys [connection developerConnection tag url]} scm]
    (xml/sexp-as-element
      (into
        [::pom/project
         {:xmlns "http://maven.apache.org/POM/4.0.0"
          (keyword "xmlns:xsi") "http://www.w3.org/2001/XMLSchema-instance"
          (keyword "xsi:schemaLocation") "http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd"}
         [::pom/modelVersion "4.0.0"]
         [::pom/packaging "jar"]
         [::pom/groupId group]
         [::pom/artifactId artifact]
         [::pom/version version]
         [::pom/name artifact]
         (gen-deps deps)
         (when (or path (seq resource-paths))
           (when (seq paths) (apply printerrln "Skipping paths:" paths))
           [::pom/build
            (when path (gen-source-dir path))
            (when (seq resource-paths) (gen-resources resource-paths))])
         (gen-repos repos)
         (when scm
           [::pom/scm
            (when connection [::pom/connection connection])
            (when developerConnection [::pom/developerConnection developerConnection])
            (when tag [::pom/tag tag])
            (when url [::pom/url url])])]
        (walk/postwalk pomify pom-data)))))

(defn- make-xml-element
  [{:keys [tag attrs] :as node} children]
  (with-meta
    (apply xml/element tag attrs children)
    (meta node)))

(defn- xml-update
  [root tag-path replace-node]
  (let [z (zip/zipper xml/element? :content make-xml-element root)]
    (zip/root
      (loop [[tag & more-tags :as tags] tag-path, parent z, child (zip/down z)]
        (if child
          (if (= tag (:tag (zip/node child)))
            (if (seq more-tags)
              (recur more-tags child (zip/down child))
              (zip/edit child (constantly replace-node)))
            (if-let [next-sibling (zip/right child)]
              (recur tags parent next-sibling)
              (if (seq more-tags)
                (let [new-parent (zip/append-child parent (xml/sexp-as-element tag))
                      new-child (zip/rightmost (zip/down new-parent))]
                  (recur more-tags new-child (zip/down new-child)))
                (zip/append-child parent replace-node))))
          (if (seq more-tags)
            (let [new-parent (zip/append-child parent (xml/sexp-as-element tag))
                  new-child (zip/rightmost (zip/down new-parent))]
              (recur more-tags new-child (zip/down new-child)))
            (zip/append-child parent replace-node)))))))

(defn- replace-deps
  [pom deps]
  (xml-update pom [::pom/dependencies] (xml/sexp-as-element (gen-deps deps))))

(defn- replace-paths
  [pom [path & paths]]
  (if path
    (do
      (when (seq paths) (apply printerrln "Skipping paths:" paths))
      (xml-update pom [::pom/build ::pom/sourceDirectory] (xml/sexp-as-element (gen-source-dir path))))
    pom))

(defn- replace-resources
  [pom resource-paths]
  (if (seq resource-paths)
    (xml-update pom [::pom/build ::pom/resources] (xml/sexp-as-element (gen-resources resource-paths)))
    pom))

(defn- replace-repos
  [pom repos]
  (if (seq repos)
    (xml-update pom [::pom/repositories] (xml/sexp-as-element (gen-repos repos)))
    pom))

(defn- replace-lib
  [pom lib]
  (if lib
    (-> pom
      (xml-update [::pom/groupId] (xml/sexp-as-element [::pom/groupId (namespace lib)]))
      (xml-update [::pom/artifactId] (xml/sexp-as-element [::pom/artifactId (name lib)]))
      (xml-update [::pom/name] (xml/sexp-as-element [::pom/name (name lib)])))
    pom))

(defn- replace-version
  [pom version]
  (if version
    (xml-update pom [::pom/version] (xml/sexp-as-element [::pom/version version]))
    pom))

(defn- replace-scm
  [pom {:keys [connection developerConnection tag url] :as scm}]
  (if scm
    (cond-> pom
      connection (xml-update [::pom/scm ::pom/connection] (xml/sexp-as-element [::pom/connection connection]))
      developerConnection (xml-update [::pom/scm ::pom/developerConnection] (xml/sexp-as-element [::pom/developerConnection developerConnection]))
      tag (xml-update [::pom/scm ::pom/tag] (xml/sexp-as-element [::pom/tag tag]))
      url (xml-update [::pom/scm ::pom/url] (xml/sexp-as-element [::pom/url url])))
    pom))

(defn- parse-xml
  [^Reader rdr]
  (let [roots (tree/seq-tree event/event-element event/event-exit? event/event-node
                (xml/event-seq rdr {:include-node? #{:element :characters :comment}
                                    :skip-whitespace true}))]
    (first (filter #(instance? Element %) (first roots)))))

(defn- libs->deps
  "Convert libmap to root deps"
  [libs]
  (reduce-kv
    (fn [ret lib {:keys [dependents] :as coord}]
      (if (seq dependents)
        ret
        (assoc ret lib coord)))
    {} libs))

(defn meta-maven-path
  [params]
  (let [{:keys [lib]} params
        pom-file (jio/file "META-INF" "maven" (namespace lib) (name lib))]
    (.toString pom-file)))

(defn write-pom
  [params]
  (let [{:keys [basis class-dir target src-pom lib version scm src-dirs resource-dirs repos pom-data]} params
        {:keys [libs]} basis
        root-deps (libs->deps libs)
        src-pom-file (when-not (= :none src-pom)
                       (api/resolve-path (or src-pom "pom.xml")))
        repos (or repos (remove #(= "https://repo1.maven.org/maven2/" (-> % val :url)) (:mvn/repos basis)))
        pom (if (and src-pom-file (.exists src-pom-file))
              (do
                (when pom-data
                  (println "Warning in write-pom: pom-data supplied but not used because pom template exists at" (or src-pom "pom.xml")))
                (with-open [rdr (jio/reader src-pom-file)]
                  (-> rdr
                    parse-xml
                    (replace-deps root-deps)
                    (replace-paths src-dirs)
                    (replace-resources resource-dirs)
                    (replace-repos repos)
                    (replace-lib lib)
                    (replace-version version)
                    (replace-scm scm))))
              (gen-pom
                (cond->
                  {:deps root-deps
                   :src-paths src-dirs
                   :resource-paths resource-dirs
                   :repos repos
                   :group (namespace lib)
                   :artifact (name lib)}
                  version (assoc :version version)
                  scm (assoc :scm scm)
                  pom-data (assoc :pom-data pom-data))))
        pom-dir-file (file/ensure-dir
                       (cond
                         class-dir (jio/file (api/resolve-path class-dir) (meta-maven-path {:lib lib}))
                         target (-> target api/resolve-path jio/file file/ensure-dir)
                         :else (throw (ex-info "write-pom requires either :class-dir or :target" {}))))]
    (spit (jio/file pom-dir-file "pom.xml") (xml/indent-str pom))
    (spit (jio/file pom-dir-file "pom.properties")
      (str/join (System/lineSeparator)
        ["# Generated by org.clojure/tools.build"
         (let [dtf (DateTimeFormatter/ofPattern "E MMM d HH:mm:ss 'UTC' u")
               inst (or (some-> "SOURCE_DATE_EPOCH"
                                System/getenv
                                parse-long
                                Instant/ofEpochSecond)
                        (Instant/now))]
           (str "# " (.format dtf (ZonedDateTime/ofInstant inst (ZoneId/of "Z")))))
         (format "version=%s" version)
         (format "groupId=%s" (namespace lib))
         (format "artifactId=%s" (name lib))]))))
