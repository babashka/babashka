#!/usr/bin/env bb
;; babashka.impl.mvn.pom's profile activation against maven-model-builder's
;; JdkVersionProfileActivatorTest, OperatingSystemProfileActivatorTest,
;; PropertyProfileActivatorTest and FileProfileActivatorTest (Maven 3.9.16),
;; the properties given instead of the JVM's.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/profile_test.clj
(ns profile-test
  (:require [babashka.fs :as fs]
            [babashka.impl.mvn.pom :as pom]
            [clojure.test :as t :refer [deftest is testing]]))

(defn- jdk [spec version] (#'pom/jdk-active? spec version))

(deftest jdk-version-test
  (testing "testNullSafe"
    (is (false? (jdk "1.4" nil)))
    (is (false? (jdk "1.4" ""))))
  (testing "testPrefix"
    (doseq [v ["1.4" "1.4.2" "1.4.2_09" "1.4.2_09-b03"]] (is (true? (jdk "1.4" v)) v))
    (doseq [v ["1.3" "1.5"]] (is (false? (jdk "1.4" v)) v)))
  (testing "testPrefixNegated"
    (doseq [v ["1.4" "1.4.2" "1.4.2_09" "1.4.2_09-b03"]] (is (false? (jdk "!1.4" v)) v))
    (doseq [v ["1.3" "1.5"]] (is (true? (jdk "!1.4" v)) v)))
  (testing "testVersionRangeInclusiveBounds"
    (doseq [v ["1.4" "1.4.2" "1.4.2_09" "1.4.2_09-b03"]] (is (false? (jdk "[1.5,1.6]" v)) v))
    (doseq [v ["1.5" "1.5.0" "1.5.0_09" "1.5.0_09-b03" "1.5.1" "1.6" "1.6.0" "1.6.0_09" "1.6.0_09-b03"]]
      (is (true? (jdk "[1.5,1.6]" v)) v)))
  (testing "testVersionRangeExclusiveBounds"
    (doseq [v ["1.3" "1.3.0" "1.3.0_09" "1.3.0_09-b03" "1.6"]] (is (false? (jdk "(1.3,1.6)" v)) v))
    (doseq [v ["1.3.1" "1.3.1_09" "1.3.1_09-b03" "1.5" "1.5.0" "1.5.0_09" "1.5.0_09-b03" "1.5.1"]]
      (is (true? (jdk "(1.3,1.6)" v)) v)))
  (testing "testVersionRangeInclusiveLowerBound"
    (doseq [v ["1.4" "1.4.2" "1.4.2_09" "1.4.2_09-b03"]] (is (false? (jdk "[1.5,)" v)) v))
    (doseq [v ["1.5" "1.5.0" "1.5.0_09" "1.5.0_09-b03" "1.5.1" "1.6" "1.6.0" "1.6.0_09" "1.6.0_09-b03"]]
      (is (true? (jdk "[1.5,)" v)) v)))
  (testing "testVersionRangeExclusiveUpperBound"
    (doseq [v ["1.5" "1.5.0" "1.5.0_09" "1.5.0_09-b03" "1.5.1"]] (is (true? (jdk "(,1.6)" v)) v))
    (doseq [v ["1.6" "1.6.0" "1.6.0_09" "1.6.0_09-b03"]] (is (false? (jdk "(,1.6)" v)) v)))
  (testing "testRubbishJavaVersion"
    (doseq [v ["Pūteketeke" "rubbish" "1.a.0_09" "1.a.2.b"]] (is (false? (jdk "[1.8,)" v)) v)))
  (testing "today's versions"
    (is (true? (jdk "[1.8,)" "25.0.4")))
    (is (true? (jdk "[17,)" "17.0.2+8")))
    (is (false? (jdk "[11,17)" "17.0.2")))))

(defn- os [m props] (#'pom/os-active? m (fn [k] (get props k))))
(defn- os-props [name version arch] {"os.name" name "os.version" version "os.arch" arch})
(def linux (os-props "linux" "6.5.0-1014-aws" "amd64"))
(def windows (os-props "windows" "6.5.0-1014-aws" "aarch64"))

(deftest operating-system-test
  (testing "testVersionStringComparison"
    (is (true? (os {:version "6.5.0-1014-aws"} linux)))
    (is (true? (os {:version "6.5.0-1014-aws"} windows)))
    (is (false? (os {:version "6.5.0-1014-aws"} (os-props "linux" "3.1.0" "amd64")))))
  (testing "testVersionRegexMatching"
    (is (true? (os {:version "regex:.*aws"} linux)))
    (is (true? (os {:version "regex:.*aws"} windows)))
    (is (false? (os {:version "regex:.*aws"} (os-props "linux" "3.1.0" "amd64")))))
  (testing "testName"
    (is (false? (os {:name "windows"} linux)))
    (is (true? (os {:name "windows"} windows))))
  (testing "testNegatedName"
    (is (true? (os {:name "!windows"} linux)))
    (is (false? (os {:name "!windows"} windows))))
  (testing "testArch"
    (is (true? (os {:arch "amd64"} linux)))
    (is (false? (os {:arch "amd64"} windows))))
  (testing "testNegatedArch"
    (is (false? (os {:arch "!amd64"} linux)))
    (is (true? (os {:arch "!amd64"} windows))))
  (testing "testFamily"
    (is (false? (os {:family "windows"} linux)))
    (is (true? (os {:family "windows"} windows))))
  (testing "testNegatedFamily"
    (is (true? (os {:family "!windows"} linux)))
    (is (false? (os {:family "!windows"} windows))))
  (testing "testAllOsConditions"
    (let [all {:family "windows" :name "windows" :arch "aarch64" :version "99"}]
      (is (false? (os all linux)))
      (is (false? (os all (os-props "windows" "1" "aarch64"))))
      (is (false? (os all (os-props "windows" "99" "amd64"))))
      (is (true? (os all (os-props "windows" "99" "aarch64"))))))
  (testing "testCapitalOsName"
    (let [mac {:family "Mac" :name "Mac OS X" :arch "aarch64" :version "14.5"}]
      (is (false? (os mac linux)))
      (is (false? (os mac (os-props "windows" "1" "aarch64"))))
      (is (false? (os mac (os-props "windows" "99" "amd64"))))
      (is (true? (os mac (os-props "Mac OS X" "14.5" "aarch64"))))))
  (testing "an empty os element activates nothing, a family Maven does not list matches the name"
    (is (false? (os {} linux)))
    (is (true? (os {:family "linux"} linux)))
    (is (false? (os {:family "linux"} windows)))))

(defn- prop [m props] (#'pom/property-active? m (fn [k] (get props k))))

(deftest property-test
  (testing "testNullSafe"
    (is (false? (prop {} {})))
    (is (false? (prop {:name nil :value nil} {}))))
  (testing "testWithNameOnly"
    (is (true? (prop {:name "prop"} {"prop" "value"})))
    (is (false? (prop {:name "prop"} {"prop" ""})))
    (is (false? (prop {:name "prop"} {"other" "value"}))))
  (testing "testWithNegatedNameOnly"
    (is (false? (prop {:name "!prop"} {"prop" "value"})))
    (is (true? (prop {:name "!prop"} {"prop" ""})))
    (is (true? (prop {:name "!prop"} {"other" "value"}))))
  (testing "testWithValue"
    (is (true? (prop {:name "prop" :value "value"} {"prop" "value"})))
    (is (false? (prop {:name "prop" :value "value"} {"prop" "other"})))
    (is (false? (prop {:name "prop" :value "value"} {"prop" ""})))
    (is (false? (prop {:name "prop" :value "value"} {"other" ""}))))
  (testing "testWithNegatedValue"
    (is (false? (prop {:name "prop" :value "!value"} {"prop" "value"})))
    (is (true? (prop {:name "prop" :value "!value"} {"prop" "other"})))
    (is (true? (prop {:name "prop" :value "!value"} {"prop" ""})))
    (is (true? (prop {:name "prop" :value "!value"} {"other" ""})))))

(defn- file [m basedir] (#'pom/file-active? m basedir))

(deftest file-test
  (let [dir (str (fs/create-temp-dir))]
    (spit (fs/file dir "file.txt") "")
    (testing "testIsActiveNoFile"
      (is (not (file {:exists nil} dir)))
      (is (not (file {:exists "someFile.txt"} dir)))
      (is (not (file {:exists "${basedir}/someFile.txt"} dir)))
      (is (not (file {:missing nil} dir)))
      (is (file {:missing "someFile.txt"} dir))
      (is (file {:missing "${basedir}/someFile.txt"} dir)))
    (testing "testIsActiveExistsFileExists"
      (is (file {:exists "file.txt"} dir))
      (is (file {:exists "${basedir}"} dir))
      (is (file {:exists "${basedir}/file.txt"} dir))
      (is (not (file {:missing "file.txt"} dir)))
      (is (not (file {:missing "${basedir}"} dir)))
      (is (not (file {:missing "${basedir}/file.txt"} dir))))
    (testing "a POM from a repository has no basedir, its file profiles stay off"
      (is (not (file {:exists "${basedir}/file.txt"} nil)))
      (is (not (file {:missing "nope.txt"} nil))))
    (fs/delete-tree dir)))

(let [{:keys [fail error]} (t/run-tests 'profile-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
