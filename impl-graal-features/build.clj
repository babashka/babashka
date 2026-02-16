(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'org.babashka/impl-graal-features)
(def version "0.0.1")
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def with-svm-basis (b/create-basis {:project "deps.edn"
                                     :aliases [:svm]}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn compile-java [_]
  (b/javac {:src-dirs ["src-java"]
            :class-dir class-dir
            :basis with-svm-basis
            :javac-opts ["--release" "21"]}))

(defn jar [opts]
  (compile-java opts)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src-java"]
                :pom-data
                [[:licenses
                  [:license
                   [:name "MIT License"]
                   [:url "https://opensource.org/license/mit"]]]]})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install [opts]
  (jar opts)
  (b/install {:basis basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn deploy [opts]
  (jar opts)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   (merge {:installer :remote
           :artifact jar-file
           :pom-file (b/pom-path {:lib lib :class-dir class-dir})}
          opts))
  opts)
