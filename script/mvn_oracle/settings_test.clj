#!/usr/bin/env bb
;; babashka.impl.mvn.settings: what resolution reads from settings.xml, and
;; mirror matching after Maven's DefaultMirrorSelector.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/settings_test.clj
(ns settings-test
  (:require [babashka.impl.mvn.settings :as settings]
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
  (is (= [{:id "internal" :url "https://nexus.example.com/maven2" :mirror-of "*,!clojars" :mirror-of-layouts nil}] (:mirrors parsed)))
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

(def matches-pattern? #'settings/matches-pattern?)
(def external? #'settings/external?)
(def matches-layout? #'settings/matches-layout?)

(defn- repo
  ([id] (repo id (str "http://" id)))
  ([id url] {:id id :url url}))

(deftest external-url-test
  (testing "external"
    (doseq [url ["http://somehost" "http://somehost:9090/somepath" "ftp://somehost" "http://192.168.101.1" "http://"]]
      (is (external? (repo "foo" url)) url)))
  (testing "local"
    (doseq [url ["http://localhost:8080" "http://127.0.0.1:9090" "file://localhost/somepath"
                 "file://localhost/D:/somepath" "http://localhost" "http://127.0.0.1"
                 "file:///somepath" "file://D:/somepath" "FILE:///somepath"]]
      (is (not (external? (repo "foo" url))) url)))
  (testing "localhost is a host, not a substring"
    (is (external? (repo "foo" "http://localhost.example.com/")))
    (is (external? (repo "foo" "https://repo.example.com/localhost/")))))

(deftest patterns-test
  (doseq [[id pattern] [["a" "*"] ["a" "*,"] ["a" ",*,"] ["a" "a"] ["a" "a,"] ["a" ",a,"]
                        ["a" "a,b"] ["b" "a,b"] ["a" "*,b"] ["a" "*,!b"] ["c" "*,!a"] ["c" "!a,*"]]]
    (is (matches-pattern? pattern (repo id)) (pr-str id pattern)))
  (doseq [[id pattern] [["b" "a"] ["b" "a,"] ["b" ",a"] ["b" ",a,"] ["c" "a,b"] ["a" "*,!a"] ["a" "!a,*"]
                        ["c" "!a,!c"] ["d" "!a,!c*"]]]
    (is (not (matches-pattern? pattern (repo id))) (pr-str id pattern))))

(deftest patterns-with-external-test
  (let [local (repo "a" "http://localhost")]
    (is (matches-pattern? "*" local))
    (is (not (matches-pattern? "external:*" local)))
    (is (matches-pattern? "external:*,a" local))
    (is (not (matches-pattern? "external:*,!a" local)))
    (is (matches-pattern? "a,external:*" local))
    (is (not (matches-pattern? "!a,external:*" local)))
    (is (not (matches-pattern? "!a,external:*" (repo "c" "http://localhost"))))
    (is (matches-pattern? "!a,external:*" (repo "c" "http://somehost")))))

(deftest layout-pattern-test
  (doseq [pattern [nil "" "*" "default" "legacy,default" "default,legacy"]]
    (is (matches-layout? pattern "default") (pr-str pattern)))
  (doseq [pattern ["legacy" "legacy,!default" "!default,legacy" "*,!default" "!default,*"]]
    (is (not (matches-layout? pattern "default")) (pr-str pattern))))

(defn- mirror
  ([id mirror-of url] (mirror id mirror-of nil url))
  ([id mirror-of layouts url] {:id id :mirror-of mirror-of :mirror-of-layouts layouts :url url}))

(deftest mirror-lookup-test
  (let [a (mirror "a" "a" "http://a")
        b (mirror "b" "b" "http://b")
        c (mirror "c" "*" "http://wildcard")]
    (testing "by id"
      (is (= a (settings/mirror-for [a b] (repo "a" "http://a.a"))))
      (is (= b (settings/mirror-for [a b] (repo "b" "http://a.a"))))
      (is (nil? (settings/mirror-for [a b] (repo "c" "http://c.c")))))
    (testing "wildcard"
      (is (= a (settings/mirror-for [a b c] (repo "a" "http://a.a"))))
      (is (= c (settings/mirror-for [a b c] (repo "c" "http://c.c")))))))

(deftest mirror-first-match-test
  (let [a2 (mirror "a2" "a,b" "http://a2")
        a (mirror "a" "a" "http://a")
        a3 (mirror "a" "a" "http://a3")
        b (mirror "b" "b" "http://b")
        c (mirror "c" "d,e" "http://de")
        c2 (mirror "c" "*" "http://wildcard")
        c3 (mirror "c" "e,f" "http://ef")
        mirrors [a2 a a3 b c c2 c3]]
    (testing "an exact id beats an earlier pattern"
      (is (= a (settings/mirror-for mirrors (repo "a" "http://a.a"))))
      (is (= b (settings/mirror-for mirrors (repo "b" "http://a.a")))))
    (testing "the first matching pattern wins"
      (is (= c2 (settings/mirror-for mirrors (repo "c" "http://c.c"))))
      (is (= c (settings/mirror-for mirrors (repo "d" "http://d"))))
      (is (= c (settings/mirror-for mirrors (repo "e" "http://e"))))
      (is (= c2 (settings/mirror-for mirrors (repo "f" "http://f")))))))

(deftest mirror-layout-test
  (let [a (mirror "a" "a" nil "http://a")
        b (mirror "b" "a" "p2" "http://b")
        c (mirror "c" "*" nil "http://c")
        d (mirror "d" "*" "p2" "http://d")]
    (is (= a (settings/mirror-for [a] (repo "a"))))
    (is (nil? (settings/mirror-for [b] (repo "a"))))
    (is (= c (settings/mirror-for [c] (repo "a"))))
    (is (nil? (settings/mirror-for [d] (repo "a"))))))

(let [{:keys [fail error]} (t/run-tests 'settings-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
