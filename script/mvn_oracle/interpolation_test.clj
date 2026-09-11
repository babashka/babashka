#!/usr/bin/env bb
;; babashka.impl.mvn.pom's interpolation against the cases of
;; maven-model-builder's AbstractModelInterpolatorTest (Maven 3.9.16) that
;; concern what resolution reads: versions, properties, repository urls.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/interpolation_test.clj
(ns interpolation-test
  (:require [babashka.impl.mvn.pom :as pom]
            [clojure.test :as t :refer [deftest is testing]]))

(defn- model
  "The effective model of a parentless POM with `body`, no repository."
  ([body] (model body nil))
  ([body basedir]
   (pom/effective-model (pom/parse (str "<project><modelVersion>4.0.0</modelVersion>"
                                        "<groupId>org.test</groupId><artifactId>foo</artifactId><version>3.8.1</version>"
                                        body "</project>"))
                        {:read-pom (constantly nil) :cache (atom {}) :basedir basedir})))

(defn- dep-version [m] (:version (first (:dependencies m))))
(defn- dep [v] (str "<dependencies><dependency><groupId>g</groupId><artifactId>a</artifactId><version>" v "</version></dependency></dependencies>"))

(deftest dependency-version-test
  (testing "testShouldInterpolateDependencyVersionToSetSameAsProjectVersion"
    (is (= "3.8.1" (dep-version (model (dep "${version}")))))
    (is (= "3.8.1" (dep-version (model (dep "${project.version}")))))
    (is (= "3.8.1" (dep-version (model (dep "${pom.version}"))))))
  (testing "testShouldNotInterpolateDependencyVersionWithInvalidReference"
    (is (= "${something}" (dep-version (model (dep "${something}"))))))
  (testing "testTwoReferences"
    (is (= "foo-3.8.1" (dep-version (model (dep "${artifactId}-${version}")))))))

(defn- property [m k] (get (:properties m) k))

(deftest properties-test
  (testing "testShouldNotThrowExceptionOnReferenceToNonExistentValue"
    (is (= "${test}/somepath" (property (model "<properties><p>${test}/somepath</p></properties>") "p"))))
  (testing "testShouldNotThrowExceptionOnReferenceToValueContainingNakedExpression"
    (is (= "test/somepath" (property (model "<properties><test>test</test><p>${test}/somepath</p></properties>") "p"))))
  (testing "testShouldThrowExceptionOnRecursiveScmConnectionReference: a self reference ends"
    (is (string? (property (model "<properties><p>${p}/somepath</p></properties>") "p"))))
  (testing "testEnvars"
    (let [home (System/getenv "HOME")]
      (is (= home (property (model "<properties><outputDirectory>${env.HOME}</outputDirectory></properties>") "outputDirectory")))))
  (testing "testEnvarExpressionThatEvaluatesToNullReturnsTheLiteralString"
    (is (= "${env.DOES_NOT_EXIST}" (property (model "<properties><p>${env.DOES_NOT_EXIST}</p></properties>") "p"))))
  (testing "testExpressionThatEvaluatesToNullReturnsTheLiteralString"
    (is (= "${DOES_NOT_EXIST}" (property (model "<properties><p>${DOES_NOT_EXIST}</p></properties>") "p"))))
  (testing "a property chain resolves through every step"
    (is (= "1.2.3" (property (model "<properties><a>1.2.3</a><b>${a}</b><c>${b}</c></properties>") "c")))))

(deftest basedir-test
  (testing "testBasedir"
    (is (= "file://localhost/myBasedir/temp-repo"
           (:url (first (:repositories (model "<repositories><repository><id>r</id><url>file://localhost/${basedir}/temp-repo</url></repository></repositories>" "myBasedir")))))))
  (testing "testShouldInterpolateUnprefixedBasedirExpression, project.basedir too"
    (is (= "/test/path/x" (property (model "<properties><p>${project.basedir}/x</p></properties>" "/test/path") "p"))))
  (testing "no basedir, a repository POM: the expression stays"
    (is (= "${basedir}/x" (property (model "<properties><p>${basedir}/x</p></properties>") "p")))))

(let [{:keys [fail error]} (t/run-tests 'interpolation-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
