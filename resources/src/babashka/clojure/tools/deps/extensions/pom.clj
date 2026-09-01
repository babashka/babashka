;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.tools.deps.extensions.pom
  (:require
    [clojure.java.io :as jio]
    [clojure.string :as str]
    [clojure.tools.deps.extensions :as ext]
    [clojure.tools.deps.util.maven :as maven]
    [clojure.tools.deps.util.session :as session])
  (:import
    [java.io File]
    [java.util List Properties]
    ;; maven-model
    [org.apache.maven.model Model Dependency Exclusion Plugin PluginExecution]
    ;; maven-model-builder
    [org.apache.maven.model.building DefaultModelBuildingRequest DefaultModelBuilderFactory ModelSource FileModelSource]
    [org.apache.maven.model.resolution ModelResolver]
    ;; maven-resolver-impl
    [org.eclipse.aether.impl RemoteRepositoryManager]
    ;; mima mmr extension
    [eu.maveniverse.maven.mima.extensions.mmr.internal ModelResolverImpl]
    ;; maven-model
    [org.apache.maven.model Resource License]
    ;; plexus-utils
    [org.codehaus.plexus.util.xml Xpp3Dom]))

(set! *warn-on-reflection* true)

(defn- model-resolver
  ^ModelResolver [{:keys [mvn/repos mvn/local-repo]} settings]
  (let [local-repo (or local-repo @maven/cached-local-repo)
        context (maven/make-context local-repo settings)
        system (.repositorySystem context)
        session (.repositorySystemSession context)
        repo-mgr ^RemoteRepositoryManager (.. context lookup (lookup RemoteRepositoryManager) (orElse nil))
        repos (maven/remote-repos system session repos)]
    (ModelResolverImpl. system session nil "project" repo-mgr repos)))

(defn read-model
  ^Model [^ModelSource source config settings]
  (let [props (Properties.)
        _ (.putAll props (System/getProperties))
        _ (.setProperty props "project.basedir" ".")
        req (doto (DefaultModelBuildingRequest.)
              (.setModelSource source)
              (.setModelResolver (model-resolver config settings))
              (.setSystemProperties props))
        builder (.newInstance (DefaultModelBuilderFactory.))
        result (.build builder req)]
    (.getEffectiveModel result)))

(defn read-model-file
  ^Model [^File file config]
  (let [settings (session/retrieve :mvn/settings #(maven/get-settings))]
    (session/retrieve
      {:pom :model :file (.getAbsolutePath file)} ;; session key
      #(read-model (FileModelSource. file) config settings))))

(defn- model-exclusions->data
  [exclusions]
  (when (and exclusions (pos? (count exclusions)))
    (into #{}
      (map (fn [^Exclusion exclusion]
             (symbol (.getGroupId exclusion) (.getArtifactId exclusion))))
      exclusions)))

(defn- is-compile
  [^Dependency dep]
  (contains? #{"compile" "runtime"} (.getScope dep)))

(defn- model-dep->data
  [^Dependency dep]
  (let [scope (.getScope dep)
        optional (.isOptional dep)
        exclusions (model-exclusions->data (.getExclusions dep))
        artifact-id (.getArtifactId dep)
        classifier (.getClassifier dep)]
    [(symbol (.getGroupId dep) (if (str/blank? classifier) artifact-id (str artifact-id "$" classifier)))
     (cond-> {:mvn/version (.getVersion dep)}
       scope (assoc :scope scope)
       optional (assoc :optional true)
       (seq exclusions) (assoc :exclusions exclusions))]))

(defn model-deps
  [^Model model]
  (->> (.getDependencies model)
    (filter is-compile)
    (map model-dep->data)))

(defmethod ext/coord-deps :pom
  [_lib {:keys [deps/root] :as _coord} _mf config]
  (let [pom (jio/file root "pom.xml")
        model (read-model-file pom config)]
    (model-deps model)))

;; Leiningen (and others) use this plugin to attach additional source dirs when the lifecycle
;; executes. To avoid executing (which has security and runtime issues), this function
;; statically extracts those attached dirs from something like this:
;;
;;     <plugin>
;;       <groupId>org.codehaus.mojo</groupId>
;;       <artifactId>build-helper-maven-plugin</artifactId>
;;       <version>1.7</version>
;;       <executions>
;;         <execution>
;;          <id>add-source</id>
;;            <phase>generate-sources</phase>
;;           <goals>
;;             <goal>add-source</goal>
;;           </goals>
;;           <configuration>
;;             <sources>
;;               <source>extra</source>  <!-- deps.edn can't see this otherwise -->
;;             </sources>
;;           </configuration>
;;         </execution>
;;       </executions>
;;     </plugin>
;; In addition, this code also includes add-resource goals including resources
(defn- get-build-helper-paths
  [^Model model]
  (let [plugins (some-> model .getBuild .getPlugins)
        build-helper-plugins (filter (fn [^Plugin plugin]
                                       (and (= "org.codehaus.mojo" (.getGroupId plugin))
                                         (= "build-helper-maven-plugin" (.getArtifactId plugin))))
                               plugins)]
    (when (seq build-helper-plugins)
      (let [^Plugin plugin (first plugins)
            extract-dirs (fn [^Plugin plugin goal ^String children]
                           (->> (.getExecutions plugin)
                             (filter (fn [^PluginExecution exec]
                                       (.contains ^List (.getGoals exec) goal)))
                             (mapcat (fn [^PluginExecution exec]
                                       (map #(.getValue ^Xpp3Dom %)
                                         (some-> exec .getConfiguration (Xpp3Dom/.getChild children) Xpp3Dom/.getChildren))))))]
        (concat
          (extract-dirs plugin "add-source" "sources")
          (extract-dirs plugin "add-resource" "resources"))))))

(defmethod ext/coord-paths :pom
  [lib {:keys [deps/root] :as _coord} _mf config]
  (let [relativize (fn [^String s]
                     (let [f (jio/file s)]
                       (if (.isAbsolute f)
                         (.getCanonicalPath f)
                         (.getCanonicalPath (jio/file root f)))))
        pom (jio/file root "pom.xml")
        model (read-model-file pom config)

        ;; Maven core 3.8.2 returns an absolute directory here, which is a breaking regression
        ;; from previous versions (see https://issues.apache.org/jira/browse/MNG-7218).
        ;; Working around this with conditional code that deals with either absolute or relative.
        ;; When MNG-7218 is fixed and deps bumped, might be able to revert the absolute path
        ;; handling in relativize
        src-dir (jio/file (.. model getBuild getSourceDirectory))

        resources (for [^Resource resource (.. model getBuild getResources)]
                    (.getDirectory resource))

        build-helper-srcs (get-build-helper-paths model)

        srcs (concat [src-dir (.getCanonicalPath (jio/file root "src/main/clojure"))]
               resources
               build-helper-srcs)]
    (->> srcs (remove nil?) (map relativize) distinct)))

(defmethod ext/manifest-file :pom
  [_lib {:keys [deps/root] :as _coord} _mf _config]
  (.getAbsolutePath (jio/file root "pom.xml")))

(defmethod ext/license-info-mf :pom
  [lib {:keys [deps/root] :as _coord} _mf config]
  (let [pom (jio/file root "pom.xml")
        model (read-model-file pom config)
        licenses (.getLicenses model)
        ^License license (when (and licenses (pos? (count licenses))) (first licenses))]
    (when license
      (let [name (.getName license)
            url (.getUrl license)]
        (when (or name url)
          (cond-> {}
            name (assoc :name name)
            url (assoc :url url)))))))

(defmethod ext/coord-usage :pom
  [lib {:keys [deps/root]} manifest-type config]
  ;; TBD
  nil)

(defmethod ext/prep-command :pom
  [lib {:keys [deps/root]} manifest-type config]
  ;; TBD
  nil)

(comment
  (ext/coord-deps 'org.clojure/core.async {:deps/root "../core.async" :deps/manifest :pom}
    :pom {:mvn/repos maven/standard-repos})

  (ext/coord-paths 'org.clojure/core.async {:deps/root "../core.async" :deps/manifest :pom}
    :pom {:mvn/repos maven/standard-repos})
  )
