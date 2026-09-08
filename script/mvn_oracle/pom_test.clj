#!/usr/bin/env bb
;; babashka.mvn.pom: the effective model from POM text, with parents,
;; properties, dependency management, BOM imports, profiles and relocation.
;; No repository: :read-pom serves POMs from a map.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/pom_test.clj
(ns pom-test
  (:require [babashka.mvn.pom :as pom]
            [clojure.test :as t :refer [deftest is testing]]))

(defn- pom [& body]
  (str "<project><modelVersion>4.0.0</modelVersion>" (apply str body) "</project>"))

(def poms
  {["org.example" "parent" "1.0"]
   (pom "<groupId>org.example</groupId><artifactId>parent</artifactId><version>1.0</version><packaging>pom</packaging>"
        "<properties><json.version>2.4.0</json.version><nested>${json.version}</nested></properties>"
        "<dependencyManagement><dependencies>"
        "<dependency><groupId>org.clojure</groupId><artifactId>data.json</artifactId><version>${json.version}</version></dependency>"
        "<dependency><groupId>org.clojure</groupId><artifactId>core.async</artifactId><version>1.6.0</version><exclusions><exclusion><groupId>org.clojure</groupId><artifactId>tools.analyzer.jvm</artifactId></exclusion></exclusions></dependency>"
        "</dependencies></dependencyManagement>"
        "<dependencies><dependency><groupId>org.clojure</groupId><artifactId>clojure</artifactId><version>1.12.0</version></dependency></dependencies>")

   ["org.example" "bom" "2.0"]
   (pom "<groupId>org.example</groupId><artifactId>bom</artifactId><version>2.0</version><packaging>pom</packaging>"
        "<dependencyManagement><dependencies>"
        "<dependency><groupId>medley</groupId><artifactId>medley</artifactId><version>1.4.0</version></dependency>"
        "</dependencies></dependencyManagement>")})

(def child
  (pom "<parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>"
       "<artifactId>child</artifactId>"
       "<dependencyManagement><dependencies>"
       "<dependency><groupId>org.example</groupId><artifactId>bom</artifactId><version>2.0</version><type>pom</type><scope>import</scope></dependency>"
       "</dependencies></dependencyManagement>"
       "<dependencies>"
       "<dependency><groupId>org.clojure</groupId><artifactId>data.json</artifactId></dependency>"
       "<dependency><groupId>org.clojure</groupId><artifactId>core.async</artifactId></dependency>"
       "<dependency><groupId>medley</groupId><artifactId>medley</artifactId></dependency>"
       "<dependency><groupId>org.example</groupId><artifactId>sibling</artifactId><version>${project.version}</version><scope>test</scope><optional>true</optional></dependency>"
       "</dependencies>"
       "<profiles>"
       "<profile><id>on</id><activation><activeByDefault>true</activeByDefault></activation>"
       "<dependencies><dependency><groupId>org.example</groupId><artifactId>from-profile</artifactId><version>${nested}</version></dependency></dependencies></profile>"
       "<profile><id>off</id><activation><property><name>babashka.mvn.no-such-property</name></property></activation>"
       "<dependencies><dependency><groupId>org.example</groupId><artifactId>never</artifactId><version>1</version></dependency></dependencies></profile>"
       "</profiles>"))

(defn- ctx []
  {:read-pom (fn [{:keys [group artifact version]} _repos]
               (get poms [group artifact version]))
   :cache (atom {})})

(defn- effective [text]
  (pom/effective-model (pom/parse text) (ctx)))

(defn- dep [model group artifact]
  (first (filter #(and (= group (:group %)) (= artifact (:artifact %))) (pom/dependencies model))))

(deftest parse-test
  (let [raw (pom/parse child)]
    (is (= {:group "org.example" :artifact "parent" :version "1.0" :relative-path nil} (:parent raw)))
    (is (= "child" (:artifact raw)))
    (is (nil? (:group raw)))
    (is (= "jar" (:packaging raw)))
    (is (= 4 (count (:dependencies raw))))
    (is (= ["on" "off"] (mapv :id (:profiles raw))))))

(deftest inheritance-test
  (let [model (effective child)]
    (testing "group and version come from the parent"
      (is (= "org.example" (:group model)))
      (is (= "1.0" (:version model))))
    (testing "the parent's dependencies are inherited"
      (is (= "1.12.0" (:version (dep model "org.clojure" "clojure")))))
    (testing "the parent's properties interpolate, through one level of nesting"
      (is (= "2.4.0" (get-in model [:properties "nested"]))))))

(deftest management-test
  (let [model (effective child)]
    (testing "a managed version and its exclusions apply to an unversioned dependency"
      (is (= "2.4.0" (:version (dep model "org.clojure" "data.json"))))
      (let [async (dep model "org.clojure" "core.async")]
        (is (= "1.6.0" (:version async)))
        (is (= [{:group "org.clojure" :artifact "tools.analyzer.jvm" :version nil}] (:exclusions async)))))
    (testing "an imported BOM manages too"
      (is (= "1.4.0" (:version (dep model "medley" "medley")))))))

(deftest interpolation-and-scope-test
  (let [model (effective child)
        sibling (dep model "org.example" "sibling")]
    (is (= "1.0" (:version sibling)))
    (is (= "test" (:scope sibling)))
    (is (true? (:optional sibling)))
    (is (= "compile" (:scope (dep model "org.clojure" "clojure"))))
    (is (false? (:optional (dep model "org.clojure" "clojure"))))))

(deftest profiles-test
  (let [model (effective child)]
    (testing "activeByDefault profiles inject dependencies, interpolated"
      (is (= "2.4.0" (:version (dep model "org.example" "from-profile")))))
    (testing "a property activation without the property stays off"
      (is (nil? (dep model "org.example" "never"))))))

(deftest relocation-test
  (let [text (pom "<groupId>xml-apis</groupId><artifactId>xml-apis</artifactId><version>2.0.2</version>"
                  "<distributionManagement><relocation><groupId>xml-apis</groupId><artifactId>xml-apis</artifactId><version>1.0.b2</version></relocation></distributionManagement>")]
    (is (= {:group "xml-apis" :artifact "xml-apis" :version "1.0.b2"} (:relocation (pom/parse text))))))

(let [{:keys [fail error]} (t/run-tests 'pom-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
