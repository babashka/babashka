;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build.tasks.test-pom
  (:require
    [clojure.test :refer :all :as test]
    [clojure.java.io :as jio]
    [clojure.string :as str]
    [clojure.zip :as zip]
    [clojure.data.xml :as xml]
    [clojure.tools.build.api :as api]
    [clojure.tools.build.tasks.write-pom :as write-pom]
    [clojure.tools.build.test-util :refer :all])
  (:import
    [java.io File InputStream]
    [java.util Properties]
    [clojure.lang ExceptionInfo]))

(set! *warn-on-reflection* true)

(xml/alias-uri 'pom "http://maven.apache.org/POM/4.0.0")

(defn read-xml
  [^File f]
  (when (.exists f)
    (#'write-pom/parse-xml (jio/reader f))))

(defn xml-path-val
  [root tag-path]
  (let [z (zip/zipper xml/element? :content nil root)]
    (loop [[tag & more-tags :as tags] tag-path, parent z, child (zip/down z)]
      (if child
        (let [node (zip/node child)]
          (if (= tag (:tag node))
            (if (seq more-tags)
              (recur more-tags child (zip/down child))
              (:content node))
            (recur tags parent (zip/right child))))
        nil))))

(defn read-props
  [^File f]
  (let [props (Properties.)]
    (when (.exists f)
      (doto props
        (.load ^InputStream (jio/input-stream f))))))

(deftest test-new-pom
  (with-test-dir "test-data/p1"
    (api/set-project-root! (.getAbsolutePath *test-dir*))
    (api/delete {:path "target"})
    (api/write-pom {;; NO :src-pom
                   :lib 'test/p1
                   :version "1.2.3"
                   :class-dir "target/classes"
                   :src-dirs ["src"]
                   :resource-dirs ["resources"]
                   :basis (api/create-basis nil)
                   :scm {:tag "v1.2.3" :url "https://github.com/example/p1"}})
    (let [pom-dir (jio/file (project-path "target/classes/META-INF/maven/test/p1"))
          pom-out (jio/file pom-dir "pom.xml")
          pom (read-xml pom-out)
          prop-out (jio/file pom-dir "pom.properties")
          props (read-props prop-out)]
      ;; check xml out
      (is (.exists pom-out))
      (are [path val] (= val (xml-path-val pom path))
        [::pom/packaging] ["jar"]
        [::pom/groupId] ["test"]
        [::pom/artifactId] ["p1"]
        [::pom/version] ["1.2.3"]
        [::pom/name] ["p1"]
        [::pom/build ::pom/sourceDirectory] ["src"]
        [::pom/build ::pom/resources ::pom/resource ::pom/directory] ["resources"]
        [::pom/scm ::pom/tag] ["v1.2.3"]
        [::pom/scm ::pom/url] ["https://github.com/example/p1"])
      (is (= 2 (count (xml-path-val pom [::pom/dependencies]))))
      (is (= 1 (count (xml-path-val pom [::pom/repositories]))))
      ;; check properties out
      (is (.exists prop-out))
      (is (submap? {"groupId" "test", "artifactId" "p1", "version" "1.2.3"} props)))))

(deftest test-update-existing-pom
  (with-test-dir "test-data/p2"
    (api/set-project-root! (.getAbsolutePath *test-dir*))
    (api/delete {:path "target"})
    (api/write-pom {:lib 'test/p2
                   :version "1.2.3"
                   :class-dir "target/classes"
                   :src-dirs ["src"]
                   :src-pom "pom.xml"
                   :resource-dirs ["resources"]
                   :basis (api/create-basis nil)
                   :scm {:tag "v1.2.3"
                         :url "https://github.com/example/p1"}})
    (let [pom-dir (jio/file (project-path "target/classes/META-INF/maven/test/p2"))
          pom-out (jio/file pom-dir "pom.xml")
          pom (read-xml pom-out)
          prop-out (jio/file pom-dir "pom.properties")
          props (read-props prop-out)]
      ;; check xml out
      (is (.exists pom-out))
      (are [path val] (= val (xml-path-val pom path))
        [::pom/packaging] ["jar"]
        [::pom/groupId] ["test"]
        [::pom/artifactId] ["p2"]
        [::pom/version] ["1.2.3"]
        [::pom/name] ["p2"]
        [::pom/build ::pom/sourceDirectory] ["src"]
        [::pom/build ::pom/resources ::pom/resource ::pom/directory] ["resources"]
        [::pom/scm ::pom/tag] ["v1.2.3"])
      (is (= 2 (count (xml-path-val pom [::pom/dependencies]))))
      (is (= 1 (count (xml-path-val pom [::pom/repositories]))))
      ;; check properties out
      (is (.exists prop-out))
      (is (submap? {"groupId" "test", "artifactId" "p2", "version" "1.2.3"} props)))))

;; check that optional deps are marked optional
(deftest test-optional
  (with-test-dir "test-data/p3"
    (api/set-project-root! (.getAbsolutePath *test-dir*))
    (api/delete {:path "target"})
    (api/write-pom {:lib 'test/p3
                   :version "1.2.3"
                   :class-dir "target/classes"
                   :src-dirs ["src"]
                   :src-pom "pom.xml"
                   :resource-dirs ["resources"]
                   :basis (api/create-basis nil)})
    (let [pom-dir (jio/file (project-path "target/classes/META-INF/maven/test/p3"))
          pom-out (jio/file pom-dir "pom.xml")]
      (is (.exists pom-out))
      (let [generated (slurp pom-out)]
        (is (str/includes? generated "core.async"))
        (is (str/includes? generated "<optional>true</optional>"))))))

;; check that supplying an empty repo map removes repos from generated pom
(deftest test-omit-repos
  (with-test-dir "test-data/p1"
    (api/set-project-root! (.getAbsolutePath *test-dir*))
    (api/delete {:path "target"})
    (api/write-pom {:lib 'test/p1
                   :version "1.2.3"
                   :class-dir "target/classes"
                   :src-dirs ["src"]
                   :src-pom "pom.xml"
                   :resource-dirs ["resources"]
                   :repos {} ;; replace repo map from deps.edn
                   :basis (api/create-basis nil)})
    (let [pom-dir (jio/file (project-path "target/classes/META-INF/maven/test/p1"))
          pom-out (jio/file pom-dir "pom.xml")]
      (is (.exists pom-out))
      (let [generated (slurp pom-out)]
        (is (not (str/includes? generated "clojars")))))))

;; check that :extra-deps in basis aliases are reflected in pom deps
(deftest test-extra-deps
  (with-test-dir "test-data/p1"
    (api/set-project-root! (.getAbsolutePath *test-dir*))
    (api/delete {:path "target"})
    (api/write-pom {:lib 'test/p1
                    :version "1.2.3"
                    :class-dir "target/classes"
                    :src-dirs ["src"]
                    :src-pom "pom.xml"
                    :resource-dirs ["resources"]
                    :basis (api/create-basis '{:extra {:aliases {:ex {:extra-deps {org.clojure/data.json {:mvn/version "2.3.1"}}}}}
                                               :aliases [:ex]})})
    (let [pom-dir (jio/file (project-path "target/classes/META-INF/maven/test/p1"))
          pom-out (jio/file pom-dir "pom.xml")]
      (is (.exists pom-out))
      (let [generated (slurp pom-out)]
        (is (str/includes? generated "data.json"))))))

(deftest test-pom-path
  (is (= (jio/file "META-INF/maven/a.b/c/pom.xml") (jio/file (api/pom-path {:lib 'a.b/c}))))

  (let [prior api/*project-root*]
    (api/set-project-root! ".")
    (is (= (jio/file "./foo/META-INF/maven/a.b/c/pom.xml")
          (jio/file (api/pom-path {:class-dir "foo" :lib 'a.b/c}))))
    (api/set-project-root! prior)))

(deftest test-validate-lib
  (with-test-dir "test-data/p1"
    (api/set-project-root! (.getAbsolutePath *test-dir*))
    (api/delete {:path "target"})
    (try
      (api/write-pom {:lib 'unqualified-lib ;; invalid
                      :version "1.2.3"
                      :class-dir "target/classes"
                      :src-dirs ["src"]
                      :src-pom "pom.xml"
                      :resource-dirs ["resources"]
                      :basis (api/create-basis nil)})
      (catch ExceptionInfo e
        (let [m (ex-message e)]
          (is (str/includes? m ":lib"))
          (is (str/includes? m "unqualified-lib"))
          (is (str/includes? m "qualified-ident?")))))))

(deftest test-target
  (with-test-dir "test-data/p1"
    (api/set-project-root! (.getAbsolutePath *test-dir*))
    (api/delete {:path "target"})
    (api/write-pom {:lib 'test/p1
                    :version "1.2.3"
                    :target "target/output-pom.xml"
                    :src-dirs ["src"]
                    :resource-dirs ["resources"]
                    :basis (api/create-basis nil)})
    (is (.exists (jio/file (project-path "target/output-pom.xml"))))))

(deftest test-target-or-class-dir
  (with-test-dir "test-data/p1"
    (api/set-project-root! (.getAbsolutePath *test-dir*))
    (api/delete {:path "target"})
    (try
      (api/write-pom {:lib 'test/p1
                      :version "1.2.3"
                      ;; NO :target or :class-dir
                      :src-dirs ["src"]
                      :src-pom "pom.xml"
                      :resource-dirs ["resources"]
                      :basis (api/create-basis nil)})
      (is false)
      (catch Throwable t
        (is true)))))

(deftest test-pom-data
  (with-test-dir "test-data/p1"
    (api/set-project-root! (.getAbsolutePath *test-dir*))
    (api/delete {:path "target"})
    (api/write-pom {:lib 'test/p1
                    :version "1.2.3"
                    :target "target"
                    :src-dirs ["src"]
                    :resource-dirs ["resources"]
                    :basis (api/create-basis nil)
                    :pom-data [[:licenses
                                [:license
                                 [:name "Apache-2.0"]
                                 [:url "https://www.apache.org/licenses/LICENSE-2.0.txt"]
                                 [:distribution "repo"]
                                 [:comments "OSS license"]]]
                               [:foo
                                [:bar "hello"]]]})
    (is (.exists (jio/file (project-path "target/pom.xml"))))
    (let [written-pom (slurp (project-path "target/pom.xml"))]
      (is (str/includes? written-pom "<name>Apache-2.0</name>"))
      (is (str/includes? written-pom "<bar>hello</bar>")))))

(comment
  (run-tests)
  (test-validate-lib)
  )
