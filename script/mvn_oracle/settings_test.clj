#!/usr/bin/env bb
;; babashka.mvn.settings: what resolution reads from settings.xml, and
;; mirror matching after Maven's DefaultMirrorSelector.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/settings_test.clj
(ns settings-test
  (:require [babashka.mvn.settings :as settings]
            [clojure.test :as t :refer [deftest is testing]]))

(def settings-xml
  "<settings>
     <localRepository>${user.home}/other-m2</localRepository>
     <servers>
       <server><id>nexus</id><username>${env.PATH}</username><password>secret</password></server>
     </servers>
     <mirrors>
       <mirror><id>internal</id><url>https://nexus.example.com/maven2</url><mirrorOf>*,!clojars</mirrorOf></mirror>
     </mirrors>
     <proxies>
       <proxy><id>corp</id><active>true</active><protocol>https</protocol><host>proxy.example.com</host><port>3128</port><nonProxyHosts>localhost|*.example.com</nonProxyHosts></proxy>
       <proxy><id>off</id><active>false</active><protocol>http</protocol><host>old.example.com</host><port>8080</port></proxy>
     </proxies>
     <profiles>
       <profile>
         <id>always</id>
         <activation><activeByDefault>true</activeByDefault></activation>
         <repositories><repository><id>always-repo</id><url>https://always.example.com/</url></repository></repositories>
         <properties><foo>bar</foo></properties>
       </profile>
       <profile>
         <id>listed</id>
         <repositories><repository><id>listed-repo</id><url>https://listed.example.com/</url></repository></repositories>
       </profile>
       <profile>
         <id>dormant</id>
         <repositories><repository><id>dormant-repo</id><url>https://dormant.example.com/</url></repository></repositories>
       </profile>
     </profiles>
     <activeProfiles><activeProfile>listed</activeProfile></activeProfiles>
   </settings>")

(def parsed (settings/parse settings-xml))

(deftest parse-test
  (is (= (str (System/getProperty "user.home") "/other-m2") (:local-repository parsed)))
  (is (= {:username (System/getenv "PATH") :password "secret"} (get-in parsed [:servers "nexus"])))
  (is (= [{:id "internal" :url "https://nexus.example.com/maven2" :mirror-of "*,!clojars"}] (:mirrors parsed)))
  (is (= [{:id "corp" :active true :protocol "https" :host "proxy.example.com" :port 3128
           :username nil :password nil :non-proxy-hosts "localhost|*.example.com"}
          {:id "off" :active false :protocol "http" :host "old.example.com" :port 8080
           :username nil :password nil :non-proxy-hosts nil}]
         (:proxies parsed)))
  (is (= ["listed"] (:active-profiles parsed)))
  (is (= {"foo" "bar"} (get-in parsed [:profiles "always" :properties])))
  (is (true? (get-in parsed [:profiles "always" :active-by-default]))))

(deftest active-profile-repositories-test
  (is (= [{:id "always-repo" :url "https://always.example.com/"}
          {:id "listed-repo" :url "https://listed.example.com/"}]
         (settings/active-profile-repositories parsed))))

(deftest interpolate-test
  (is (= "x" (settings/interpolate "x")))
  (is (nil? (settings/interpolate nil)))
  (is (= (System/getProperty "user.home") (settings/interpolate "${user.home}")))
  (is (= "${no.such.property}" (settings/interpolate "${no.such.property}"))))

(deftest mirror-for-test
  (let [central {:id "central" :url "https://repo1.maven.org/maven2/"}
        clojars {:id "clojars" :url "https://repo.clojars.org/"}
        local {:id "local" :url "file:///tmp/repo/"}
        insecure {:id "insecure" :url "http://repo.example.com/"}
        m (fn [pattern] [{:id "m" :url "https://m/" :mirror-of pattern}])]
    (testing "id, wildcard, exclusion"
      (is (some? (settings/mirror-for (m "central") central)))
      (is (nil? (settings/mirror-for (m "central") clojars)))
      (is (some? (settings/mirror-for (m "*") clojars)))
      (is (nil? (settings/mirror-for (m "*,!clojars") clojars)))
      (is (some? (settings/mirror-for (m "*,!clojars") central))))
    (testing "external:* skips file and localhost repositories"
      (is (some? (settings/mirror-for (m "external:*") central)))
      (is (nil? (settings/mirror-for (m "external:*") local)))
      (is (nil? (settings/mirror-for (m "external:*") {:id "l" :url "http://localhost:8080/"}))))
    (testing "external:http:* only matches plain http"
      (is (some? (settings/mirror-for (m "external:http:*") insecure)))
      (is (nil? (settings/mirror-for (m "external:http:*") central))))
    (testing "the first matching mirror wins"
      (is (= "first" (:id (settings/mirror-for [{:id "first" :url "https://a/" :mirror-of "central"}
                                                {:id "second" :url "https://b/" :mirror-of "*"}]
                                               central)))))))

(let [{:keys [fail error]} (t/run-tests 'settings-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
