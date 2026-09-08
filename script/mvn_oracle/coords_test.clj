#!/usr/bin/env bb
;; babashka.mvn.coords: lib names, artifact maps and the repository layout,
;; including where Aether keeps a timestamped snapshot.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/coords_test.clj
(ns coords-test
  (:require [babashka.mvn.coords :as coords]
            [clojure.test :as t :refer [deftest is testing]]))

(deftest lib-names-test
  (is (= ["org.clojure" "clojure" nil] (coords/lib->names 'org.clojure/clojure)))
  (is (= ["medley" "medley" nil] (coords/lib->names 'medley/medley)))
  (is (= ["medley" "medley" nil] (coords/lib->names 'medley)))
  (is (= ["io.netty" "netty-transport-native-epoll" "linux-x86_64"]
         (coords/lib->names 'io.netty/netty-transport-native-epoll$linux-x86_64))))

(deftest artifact-test
  (is (= {:group "org.clojure" :artifact "clojure" :version "1.12.0" :extension "jar"}
         (coords/artifact 'org.clojure/clojure {:mvn/version "1.12.0"})))
  (is (= {:group "io.netty" :artifact "netty-transport-native-epoll" :version "4.1.0.Final"
          :extension "jar" :classifier "linux-x86_64"}
         (coords/artifact 'io.netty/netty-transport-native-epoll$linux-x86_64 {:mvn/version "4.1.0.Final"})))
  (is (= "pom" (:extension (coords/artifact 'a/b {:mvn/version "1" :extension "pom"}))))
  (testing ":classifier in a coordinate is refused the way tools.deps refuses it"
    (is (thrown-with-msg? Exception #"groupId/artifactId\$classifier"
                          (coords/artifact 'a/b {:mvn/version "1" :classifier "tests"})))))

(deftest packaging-types-test
  (is (= "jar" (coords/type->extension "bundle")))
  (is (= "jar" (coords/type->extension "maven-plugin")))
  (is (= "war" (coords/type->extension "war")))
  (is (= "tests" (coords/type->classifier "test-jar")))
  (is (nil? (coords/type->classifier "jar"))))

(deftest layout-test
  (let [release (coords/artifact 'org.clojure/data.json {:mvn/version "2.4.0"})
        classified (coords/artifact 'io.netty/netty-transport-native-epoll$linux-x86_64 {:mvn/version "4.1.0.Final"})]
    (is (= "org/clojure/data.json/2.4.0/data.json-2.4.0.jar" (coords/relative-path release)))
    (is (= (coords/relative-path release) (coords/local-relative-path release)))
    (is (= "io/netty/netty-transport-native-epoll/4.1.0.Final/netty-transport-native-epoll-4.1.0.Final-linux-x86_64.jar"
           (coords/relative-path classified)))))

(deftest snapshot-layout-test
  (testing "a timestamped snapshot lives in the -SNAPSHOT directory, and Aether stores it under the base name"
    (let [a (coords/artifact 'clj-kondo/clj-kondo {:mvn/version "2025.10.24-20251024.101010-3"})]
      (is (= "2025.10.24-SNAPSHOT" (coords/base-version "2025.10.24-20251024.101010-3")))
      (is (coords/snapshot? "2025.10.24-20251024.101010-3"))
      (is (coords/snapshot? "2025.10.24-SNAPSHOT"))
      (is (not (coords/snapshot? "2025.10.24")))
      (is (= "clj-kondo/clj-kondo/2025.10.24-SNAPSHOT/clj-kondo-2025.10.24-20251024.101010-3.jar"
             (coords/relative-path a)))
      (is (= "clj-kondo/clj-kondo/2025.10.24-SNAPSHOT/clj-kondo-2025.10.24-SNAPSHOT.jar"
             (coords/local-relative-path a))))))

(deftest version-range-test
  (is (coords/version-range? "[2.4,2.5)"))
  (is (coords/version-range? "(,1.0]"))
  (is (not (coords/version-range? "1.0")))
  (is (not (coords/version-range? nil))))

(let [{:keys [fail error]} (t/run-tests 'coords-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
