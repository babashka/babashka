#!/usr/bin/env bb
;; POMs that are missing or do not parse, resolved without dependencies.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/unreadable_pom_test.clj
(ns unreadable-pom-test
  (:require [babashka.classpath :as cp]
            [babashka.deps :as deps]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [clojure.tools.deps.extensions :as ext]
            [clojure.tools.deps.extensions.maven]
            [clojure.tools.deps.util.session :as session]))

;; add-deps resolves earlier libs again, so all tests share one repository
(def ^:private tmp (fs/create-temp-dir))
(def ^:private remote (fs/file tmp "remote"))
(def ^:private local (fs/file tmp "local"))

(defn- pom [artifact & body]
  (str "<project><modelVersion>4.0.0</modelVersion>"
       "<groupId>bad</groupId><artifactId>" artifact "</artifactId><version>1.0.0</version>"
       (apply str body) "</project>"))

(def ^:private missing-dependency
  "A dependency no repository has. Resolving it fails."
  "<dependencies><dependency><groupId>nope</groupId><artifactId>nope</artifactId><version>1.0.0</version></dependency></dependencies>")

(defn- publish!
  "Writes a jar for bad/<artifact> 1.0.0, and its POM unless pom-text is nil."
  [artifact pom-text]
  (let [d (fs/file remote "bad" artifact "1.0.0")]
    (fs/create-dirs d)
    (when pom-text (spit (fs/file d (str artifact "-1.0.0.pom")) pom-text))
    (spit (fs/file d (str artifact "-1.0.0.jar")) "PK")))

(defn- resolve!
  "Adds bad/<artifact> 1.0.0 and returns what was written to *err*."
  [artifact]
  (let [err (java.io.StringWriter.)]
    (binding [*err* err]
      (deps/add-deps {:deps {(symbol "bad" artifact) {:mvn/version "1.0.0"}}
                      :mvn/repos {"remote" {:url (str (.toURI remote))}}
                      :mvn/local-repo (str local)
                      :deps-resolver :bb}
                     {:force true}))
    (str err)))

(defn- on-classpath? [artifact]
  (some #(str/ends-with? % (str artifact "-1.0.0.jar")) (cp/split-classpath (cp/get-classpath))))

(deftest damaged-local-pom-test
  (testing "a damaged POM in the local repository is ignored with a warning"
    (publish! "damaged" (pom "damaged" missing-dependency))
    (fs/create-dirs (fs/file local "bad" "damaged" "1.0.0"))
    (spit (fs/file local "bad" "damaged" "1.0.0" "damaged-1.0.0.pom") "not xml at all")
    (is (str/includes? (resolve! "damaged") "WARNING: The POM for bad:damaged:1.0.0 is invalid"))
    (is (on-classpath? "damaged"))))

(deftest parse-error-test
  (testing "a POM with an undeclared entity is ignored with a warning"
    (publish! "entity" (pom "entity" "<name>&bogus;</name>" missing-dependency))
    (is (str/includes? (resolve! "entity") "WARNING: The POM for bad:entity:1.0.0 is invalid"))
    (is (on-classpath? "entity")))
  (testing "a POM with content after the root element is ignored with a warning"
    (publish! "junk" (str (pom "junk" missing-dependency) "<<<junk"))
    (is (str/includes? (resolve! "junk") "WARNING: The POM for bad:junk:1.0.0 is invalid"))
    (is (on-classpath? "junk"))))

(deftest unreadable-parent-test
  (testing "a parent that does not parse leaves the child without dependencies"
    (publish! "parent" "not xml at all")
    (publish! "child" (str "<project><modelVersion>4.0.0</modelVersion>"
                           "<parent><groupId>bad</groupId><artifactId>parent</artifactId><version>1.0.0</version></parent>"
                           "<artifactId>child</artifactId>" missing-dependency "</project>"))
    (is (str/includes? (resolve! "child") "WARNING: The POM for bad:child:1.0.0 is invalid"))
    (is (on-classpath? "child"))))

(deftest warn-once-test
  (testing "an invalid POM warns once per session"
    (publish! "twice" "not xml at all")
    (let [err (java.io.StringWriter.)
          config {:mvn/repos {"remote" {:url (str (.toURI remote))}}
                  :mvn/local-repo (str local)}]
      (session/with-session
        (binding [*err* err]
          (dotimes [_ 2]
            (is (= [] (ext/coord-deps 'bad/twice {:mvn/version "1.0.0"} :mvn config))))))
      (is (= 1 (count (re-seq #"The POM for bad:twice:1\.0\.0 is invalid" (str err))))))))

(deftest bom-cycle-test
  (testing "BOM imports that form a cycle leave the importing artifact without dependencies"
    (let [import (fn [artifact]
                   (str "<dependencyManagement><dependencies><dependency><groupId>bad</groupId><artifactId>" artifact
                        "</artifactId><version>1.0.0</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>"))]
      (publish! "bom-x" (pom "bom-x" "<packaging>pom</packaging>" (import "bom-y")))
      (publish! "bom-y" (pom "bom-y" "<packaging>pom</packaging>" (import "bom-x")))
      (publish! "bom-user" (pom "bom-user" (import "bom-x") missing-dependency))
      (is (str/includes? (resolve! "bom-user")
                         (str "WARNING: The POM for bad:bom-user:1.0.0 is invalid, transitive dependencies will not be available: "
                              "The dependencies of type=pom and with scope=import form a cycle: "
                              "bad:bom-user:1.0.0 -> bad:bom-x:1.0.0 -> bad:bom-y:1.0.0 -> bad:bom-x:1.0.0")))
      (is (on-classpath? "bom-user")))))

(deftest missing-pom-test
  (testing "a jar without a POM resolves"
    (publish! "nopom" nil)
    (is (not (str/includes? (resolve! "nopom") "bad:nopom")))
    (is (on-classpath? "nopom"))))

(let [{:keys [fail error]} (t/run-tests 'unreadable-pom-test)]
  (fs/delete-tree tmp)
  (System/exit (if (zero? (+ fail error)) 0 1)))
