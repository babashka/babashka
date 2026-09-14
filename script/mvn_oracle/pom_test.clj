#!/usr/bin/env bb
;; babashka.impl.mvn.pom: the effective model from POM text, with parents,
;; properties, dependency management, BOM imports, profiles and relocation.
;; No repository: :read-pom serves POMs from a map.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/pom_test.clj
(ns pom-test
  (:require [babashka.impl.mvn.pom :as pom]
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
       "<profile><id>off</id><activation><property><name>babashka.impl.mvn.no-such-property</name></property></activation>"
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
    (is (= ["on" "off"] (mapv :id (:profiles raw)))))
  (testing "a POM with a byte order mark and HTML entities parses"
    (let [raw (pom/parse (str "\uFEFF" (pom "<groupId>g</groupId><artifactId>a</artifactId><version>1</version>"
                                            "<properties><name>caf&eacute;&nbsp;&amp;</name></properties>")))]
      (is (= "a" (:artifact raw)))
      (is (= "caf\u00E9\u00A0&" (get-in raw [:properties "name"])))))
  (testing "text that does not parse throws :unreadable"
    (is (= :babashka.impl.mvn.pom/unreadable
           (:type (ex-data (try (pom/parse "not xml at all") (catch Exception e e))))))))

(deftest duplicate-declarations-test
  ;; netty-all's flattened POM declares one artifact three times; Maven's
  ;; normalizer keeps the last declaration, in the place of the first
  (let [deps (:dependencies
              (pom/parse
               (pom "<groupId>g</groupId><artifactId>a</artifactId><version>1</version><dependencies>"
                    "<dependency><groupId>io.netty</groupId><artifactId>epoll</artifactId><version>4.2</version><classifier>linux-riscv64</classifier><scope>runtime</scope>"
                    "<exclusions><exclusion><groupId>io.netty</groupId><artifactId>common</artifactId></exclusion></exclusions></dependency>"
                    "<dependency><groupId>io.netty</groupId><artifactId>epoll</artifactId><version>4.2</version><classifier>linux-x86_64</classifier><scope>runtime</scope></dependency>"
                    "<dependency><groupId>io.netty</groupId><artifactId>epoll</artifactId><version>4.2</version><classifier>linux-riscv64</classifier><scope>runtime</scope></dependency>"
                    "<dependency><groupId>io.netty</groupId><artifactId>epoll</artifactId><version>4.2</version><classifier>linux-riscv64</classifier><scope>runtime</scope><optional>true</optional>"
                    "<exclusions><exclusion><groupId>io.netty</groupId><artifactId>buffer</artifactId></exclusion></exclusions></dependency>"
                    "</dependencies>")))]
    (is (= ["linux-riscv64" "linux-x86_64"] (mapv :classifier deps)) "the first declaration's place")
    (is (= "true" (:optional (first deps))) "the last declaration's fields")
    (is (= ["buffer"] (mapv :artifact (:exclusions (first deps)))) "the last declaration's exclusions, no union")))

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
      (is (= "1.4.0" (:version (dep model "medley" "medley"))))))
  (testing "a managed optional does not apply"
    (let [model (effective (pom "<groupId>org.example</groupId><artifactId>managed-optional</artifactId><version>1</version>"
                                "<dependencyManagement><dependencies>"
                                "<dependency><groupId>medley</groupId><artifactId>medley</artifactId><version>1.4.0</version><optional>true</optional></dependency>"
                                "</dependencies></dependencyManagement>"
                                "<dependencies><dependency><groupId>medley</groupId><artifactId>medley</artifactId></dependency></dependencies>"))]
      (is (= "1.4.0" (:version (dep model "medley" "medley"))))
      (is (false? (:optional (dep model "medley" "medley")))))))

(deftest inherited-coordinates-cache-test
  (let [bom-parent (fn [v] (pom "<groupId>org.example</groupId><artifactId>bom-parent</artifactId><version>" v "</version><packaging>pom</packaging>"))
        bom (fn [v managed]
              (pom "<parent><groupId>org.example</groupId><artifactId>bom-parent</artifactId><version>" v "</version></parent>"
                   "<artifactId>inherited-bom</artifactId><packaging>pom</packaging>"
                   "<dependencyManagement><dependencies>" managed "</dependencies></dependencyManagement>"))
        poms {["org.example" "bom-parent" "1"] (bom-parent "1")
              ["org.example" "bom-parent" "2"] (bom-parent "2")
              ["org.example" "inherited-bom" "1"] (bom "1" "")
              ["org.example" "inherited-bom" "2"] (bom "2" "<dependency><groupId>medley</groupId><artifactId>medley</artifactId><version>1.4.0</version></dependency>")}
        consumer (fn [v]
                   (pom "<groupId>org.example</groupId><artifactId>consumer-" v "</artifactId><version>1</version>"
                        "<dependencyManagement><dependencies>"
                        "<dependency><groupId>org.example</groupId><artifactId>inherited-bom</artifactId><version>" v "</version><type>pom</type><scope>import</scope></dependency>"
                        "</dependencies></dependencyManagement>"
                        "<dependencies><dependency><groupId>medley</groupId><artifactId>medley</artifactId></dependency></dependencies>"))
        ctx {:read-pom (fn [{:keys [group artifact version]} _repos] (get poms [group artifact version]))
             :cache (atom {})}]
    (pom/effective-model (pom/parse (consumer "1")) ctx)
    (testing "a BOM that inherits its version is cached per version"
      (is (= "1.4.0" (:version (dep (pom/effective-model (pom/parse (consumer "2")) ctx) "medley" "medley")))))))

(deftest requested-coordinates-cache-test
  (testing "a BOM whose version is a property is cached per requested version"
    (let [bom (fn [v managed]
                (pom "<groupId>org.example</groupId><artifactId>revision-bom</artifactId><version>${revision}</version><packaging>pom</packaging>"
                     "<properties><revision>" v "</revision></properties>"
                     "<dependencyManagement><dependencies>" managed "</dependencies></dependencyManagement>"))
          poms {["org.example" "revision-bom" "1"] (bom "1" "")
                ["org.example" "revision-bom" "2"] (bom "2" "<dependency><groupId>medley</groupId><artifactId>medley</artifactId><version>1.4.0</version></dependency>")}
          consumer (fn [v]
                     (pom "<groupId>org.example</groupId><artifactId>consumer-" v "</artifactId><version>1</version>"
                          "<dependencyManagement><dependencies>"
                          "<dependency><groupId>org.example</groupId><artifactId>revision-bom</artifactId><version>" v "</version><type>pom</type><scope>import</scope></dependency>"
                          "</dependencies></dependencyManagement>"
                          "<dependencies><dependency><groupId>medley</groupId><artifactId>medley</artifactId></dependency></dependencies>"))
          ctx {:read-pom (fn [{:keys [group artifact version]} _repos] (get poms [group artifact version]))
               :cache (atom {})}]
      (pom/effective-model (pom/parse (consumer "1")) ctx)
      (is (= "1.4.0" (:version (dep (pom/effective-model (pom/parse (consumer "2")) ctx) "medley" "medley")))))))

(deftest interpolation-and-scope-test
  (let [model (effective child)
        sibling (dep model "org.example" "sibling")]
    (is (= "1.0" (:version sibling)))
    (is (= "test" (:scope sibling)))
    (is (true? (:optional sibling)))
    (is (= "compile" (:scope (dep model "org.clojure" "clojure"))))
    (is (false? (:optional (dep model "org.clojure" "clojure"))))))

(deftest expression-order-test
  (let [model (effective (pom "<groupId>org.example</groupId><artifactId>order</artifactId><version>1</version>"
                              "<properties><version>3</version><project.version>9</project.version></properties>"
                              "<dependencies>"
                              "<dependency><groupId>org.example</groupId><artifactId>a</artifactId><version>${version}</version></dependency>"
                              "<dependency><groupId>org.example</groupId><artifactId>b</artifactId><version>${project.version}</version></dependency>"
                              "</dependencies>"))]
    (testing "a property wins over an unprefixed model expression"
      (is (= "3" (:version (dep model "org.example" "a")))))
    (testing "a project. model expression wins over a property"
      (is (= "1" (:version (dep model "org.example" "b")))))))

(deftest profiles-test
  (let [model (effective child)]
    (testing "activeByDefault profiles inject dependencies, interpolated"
      (is (= "2.4.0" (:version (dep model "org.example" "from-profile")))))
    (testing "a property activation without the property stays off"
      (is (nil? (dep model "org.example" "never")))))
  (testing "optional and activeByDefault ignore case, as Maven's POM reader parses them"
    (let [model (effective (pom "<groupId>org.example</groupId><artifactId>booleans</artifactId><version>1</version>"
                                "<dependencies><dependency><groupId>medley</groupId><artifactId>medley</artifactId><version>1.4.0</version><optional>TRUE</optional></dependency></dependencies>"
                                "<profiles><profile><id>on</id><activation><activeByDefault>True</activeByDefault></activation>"
                                "<dependencies><dependency><groupId>org.example</groupId><artifactId>from-profile</artifactId><version>1</version></dependency></dependencies>"
                                "</profile></profiles>"))]
      (is (true? (:optional (dep model "medley" "medley"))))
      (is (some? (dep model "org.example" "from-profile"))))))

(deftest disk-parent-test
  (testing "a parent on disk wins over a copy of it cached from a repository"
    (let [parent (fn [managed]
                   (pom "<groupId>org.example</groupId><artifactId>shared-parent</artifactId><version>1</version><packaging>pom</packaging>"
                        "<dependencyManagement><dependencies>" managed "</dependencies></dependencyManagement>"))
          repo-parent (parent "")
          disk-parent (parent "<dependency><groupId>medley</groupId><artifactId>medley</artifactId><version>1.4.0</version></dependency>")
          child (fn [artifact]
                  (pom "<parent><groupId>org.example</groupId><artifactId>shared-parent</artifactId><version>1</version></parent>"
                       "<artifactId>" artifact "</artifactId>"
                       "<dependencies><dependency><groupId>medley</groupId><artifactId>medley</artifactId></dependency></dependencies>"))
          ctx {:read-pom (fn [{:keys [basedir]} _repos]
                           (if basedir {:text disk-parent :basedir "/checkout"} repo-parent))
               :cache (atom {})}]
      (pom/effective-model (pom/parse (child "from-repo"))
                           (assoc ctx :coords {:group "org.example" :artifact "from-repo" :version "1"}))
      (is (= "1.4.0" (:version (dep (pom/effective-model (pom/parse (child "local")) (assoc ctx :basedir "/checkout/local"))
                                    "medley" "medley")))))))

(deftest parent-version-range-test
  (let [poms {["org.example" "ranged-parent" "2.0"]
              (pom "<groupId>org.example</groupId><artifactId>ranged-parent</artifactId><version>2.0</version><packaging>pom</packaging>")}
        child (pom "<parent><groupId>org.example</groupId><artifactId>ranged-parent</artifactId><version>[1.0,3.0)</version></parent>"
                   "<artifactId>ranged-child</artifactId><version>1</version>"
                   "<dependencies><dependency><groupId>org.example</groupId><artifactId>sibling</artifactId><version>${project.parent.version}</version></dependency></dependencies>")
        ctx {:read-pom (fn [{:keys [group artifact version]} _repos] (get poms [group artifact version]))
             :resolve-version (fn [_parent _repos] "2.0")
             :cache (atom {})}
        model (pom/effective-model (pom/parse child) ctx)]
    (testing "the parent's version is the version resolve-version returns"
      (is (= "2.0" (get-in model [:parent :version]))))
    (testing "${project.parent.version} is the resolved version"
      (is (= "2.0" (:version (dep model "org.example" "sibling")))))))

(defn- failure
  "The ex-info the effective model of text throws with poms as the repository."
  [poms text]
  (try (pom/effective-model (pom/parse text)
                            {:read-pom (fn [{:keys [group artifact version]} _repos] (get poms [group artifact version]))
                             :cache (atom {})})
       nil
       (catch clojure.lang.ExceptionInfo e e)))

(deftest cycle-test
  (testing "a property cycle fails with plexus-interpolation's message"
    (let [e (failure {} (pom "<groupId>org.example</groupId><artifactId>props</artifactId><version>1</version>"
                             "<properties><a>${b}</a><b>${a}</b></properties>"))]
      (is (= :babashka.impl.mvn.pom/invalid (:type (ex-data e))))
      (is (re-matches #"Resolving expression: '\$\{([ab])\}': Detected the following recursive expression cycle in '\1': \[(a, b|b, a)\]"
                      (ex-message e)))))
  (testing "parents that form a cycle fail with Maven's message"
    (let [poms {["org.example" "cycle-a" "1"]
                (pom "<parent><groupId>org.example</groupId><artifactId>cycle-b</artifactId><version>1</version></parent>"
                     "<artifactId>cycle-a</artifactId><packaging>pom</packaging>")
                ["org.example" "cycle-b" "1"]
                (pom "<parent><groupId>org.example</groupId><artifactId>cycle-a</artifactId><version>1</version></parent>"
                     "<artifactId>cycle-b</artifactId><packaging>pom</packaging>")}
          e (failure poms (pom "<parent><groupId>org.example</groupId><artifactId>cycle-a</artifactId><version>1</version></parent>"
                               "<artifactId>cycle-child</artifactId>"))]
      (is (= :babashka.impl.mvn.pom/invalid (:type (ex-data e))))
      (is (= "The parents form a cycle: org.example:cycle-child:1 -> org.example:cycle-a:1 -> org.example:cycle-b:1 -> org.example:cycle-a:1"
             (ex-message e)))))
  (testing "BOM imports that form a cycle fail with Maven's message"
    (let [import (fn [artifact]
                   (str "<dependencyManagement><dependencies><dependency><groupId>org.example</groupId><artifactId>" artifact
                        "</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>"))
          poms {["org.example" "bom-x" "1"]
                (pom "<groupId>org.example</groupId><artifactId>bom-x</artifactId><version>1</version><packaging>pom</packaging>" (import "bom-y"))
                ["org.example" "bom-y" "1"]
                (pom "<groupId>org.example</groupId><artifactId>bom-y</artifactId><version>1</version><packaging>pom</packaging>" (import "bom-x"))}
          e (failure poms (pom "<groupId>org.example</groupId><artifactId>consumer</artifactId><version>1</version>" (import "bom-x")))]
      (is (= :babashka.impl.mvn.pom/invalid (:type (ex-data e))))
      (is (= "The dependencies of type=pom and with scope=import form a cycle: org.example:consumer:1 -> org.example:bom-x:1 -> org.example:bom-y:1 -> org.example:bom-x:1"
             (ex-message e))))))

(deftest relocation-test
  (let [text (pom "<groupId>xml-apis</groupId><artifactId>xml-apis</artifactId><version>2.0.2</version>"
                  "<distributionManagement><relocation><groupId>xml-apis</groupId><artifactId>xml-apis</artifactId><version>1.0.b2</version></relocation></distributionManagement>")]
    (is (= {:group "xml-apis" :artifact "xml-apis" :version "1.0.b2"} (:relocation (pom/parse text))))))

(let [{:keys [fail error]} (t/run-tests 'pom-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
