(ns build
  (:require [clojure.edn :as edn]
            [clojure.tools.build.api :as b]))

;; use neil project set version x.y.z to update the version in deps.edn
(def project (-> (edn/read-string (slurp "deps.edn"))
                 :aliases :neil :project))
(def lib (:name project))
(def version (:version project))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def generated-class "target/classes/babashka/impl/java/io/Closeable.class")

(defn clean [_]
  (b/delete {:path "target"}))

(defn gen-classes
  "Writes the reify classes to target/classes, on the JVM: insn needs ASM."
  [_]
  ((requiring-resolve 'build.reify2/gen-classes) nil))

(defn compile-java [_]
  (b/javac {:src-dirs ["src-java"]
            :class-dir class-dir
            :basis basis
            :javac-opts ["--release" "8"]}))

(defn jar [_]
  (when-not (.exists (java.io.File. generated-class))
    (throw (ex-info "The reify classes are missing, run bb gen-classes first" {})))
  (compile-java nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]
                :pom-data
                [[:licenses
                  [:license
                   [:name "MIT License"]
                   [:url "https://opensource.org/license/mit/"]]]]})
  (b/copy-dir {:src-dirs ["src"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install [_]
  (jar nil)
  (b/install {:basis basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn deploy [opts]
  (jar opts)
  ((requiring-resolve 'babashka.deps-deploy/deploy)
   (merge {:installer :remote
           :artifact jar-file
           :pom-file (b/pom-path {:lib lib :class-dir class-dir})}
          opts))
  opts)
