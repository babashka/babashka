;; Run on the JVM with the tools.deps jars on the classpath:
;;   clojure -Sdeps '{:deps {org.clojure/tools.deps {:mvn/version "0.31.1638"}}}' -M target/tools-deps/precise-methods.clj
;; Prints a custom-map fragment: for each class in tools-deps-classes, the
;; public methods whose names the sources use, plus <init> where constructed.
(require '[clojure.string :as str])

(def used (set (str/split-lines (slurp "target/tools-deps/method-names.txt"))))

(defn def-value
  "The quoted value of (def name (quote value)) in classes.clj."
  [name]
  (let [src (slurp "src/babashka/impl/classes.clj")
        start (str/index-of src (str "(def " name))
        form (read-string (subs src start))]
    (second (nth form 2))))

(def classes
  (sort (concat (keys (def-value "tools-deps-methods"))
                (def-value "tools-deps-name-only"))))

(def constructed
  '#{org.eclipse.aether.resolution.ArtifactDescriptorRequest
     org.eclipse.aether.resolution.ArtifactRequest
     org.eclipse.aether.artifact.DefaultArtifact
     org.apache.maven.model.building.DefaultModelBuilderFactory
     org.apache.maven.model.building.DefaultModelBuildingRequest
     org.apache.maven.settings.building.DefaultSettingsBuilderFactory
     org.apache.maven.settings.building.DefaultSettingsBuildingRequest
     org.apache.maven.model.building.FileModelSource
     org.eclipse.aether.util.version.GenericVersionScheme
     org.eclipse.aether.repository.LocalRepository
     eu.maveniverse.maven.mima.extensions.mmr.internal.ModelResolverImpl
     org.eclipse.aether.repository.RemoteRepository$Builder
     org.eclipse.aether.repository.RepositoryPolicy
     org.apache.maven.model.building.UrlModelSource
     org.eclipse.aether.resolution.VersionRangeRequest
     org.eclipse.aether.resolution.VersionRequest
     eu.maveniverse.maven.mima.runtime.standalonestatic.StandaloneStaticRuntime})

(defn type-name [^Class t]
  (if (.isArray t)
    (str (type-name (.getComponentType t)) "[]")
    (.getName t)))

(defn entry [name ^"[Ljava.lang.Class;" params]
  {:name name
   :parameterTypes (mapv type-name params)})

(def precise
  (into (sorted-map)
        (for [c classes
              :let [cls (Class/forName (str c))
                    methods (->> (.getMethods cls)
                                 (filter #(used (.getName ^java.lang.reflect.Method %)))
                                 (map #(entry (.getName ^java.lang.reflect.Method %)
                                              (.getParameterTypes ^java.lang.reflect.Method %)))
                                 distinct
                                 (sort-by (juxt :name :parameterTypes))
                                 vec)
                    ctors (when (constructed c)
                            (->> (.getConstructors cls)
                                 (map #(entry "<init>" (.getParameterTypes ^java.lang.reflect.Constructor %)))
                                 (sort-by :parameterTypes)
                                 vec))
                    entries (into methods ctors)]
              :when (seq entries)]
          [c {:methods entries}])))

(def name-only (remove (set (keys precise)) classes))

(println ";; precise")
(prn precise)
(println ";; name-only")
(prn (vec name-only))
