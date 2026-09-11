#!/usr/bin/env bb
;; babashka.impl.mvn.coords against Maven2RepositoryLayoutFactoryTest:
;; where an artifact and its metadata live in a repository.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/layout_test.clj
(ns layout-test
  (:require [babashka.impl.mvn.coords :as coords]
            [clojure.test :as t :refer [deftest is testing]]))

(def release {:group "g.i.d" :artifact "a-i.d" :classifier "cls" :extension "ext" :version "1.0"})
(def snapshot (assoc release :version "1.0-20110329.221805-4"))

(deftest artifact-location-test
  (testing "release"
    (is (= "g/i/d/a-i.d/1.0/a-i.d-1.0-cls.ext" (coords/relative-path release))))
  (testing "snapshot"
    (is (= "g/i/d/a-i.d/1.0-SNAPSHOT/a-i.d-1.0-20110329.221805-4-cls.ext" (coords/relative-path snapshot)))))

(deftest metadata-location-test
  (let [plugin {:group "org.apache.maven.plugins" :artifact "maven-jar-plugin" :version "1.0-SNAPSHOT"}]
    (testing "artifact level"
      (is (= "org/apache/maven/plugins/maven-jar-plugin/maven-metadata.xml"
             (str (coords/artifact-dir plugin) "/maven-metadata.xml"))))
    (testing "version level"
      (is (= "org/apache/maven/plugins/maven-jar-plugin/1.0-SNAPSHOT/maven-metadata.xml"
             (str (coords/version-dir plugin) "/maven-metadata.xml"))))))

(let [{:keys [fail error]} (t/run-tests 'layout-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
