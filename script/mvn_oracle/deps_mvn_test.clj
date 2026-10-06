#!/usr/bin/env bb
;; babashka.deps.mvn: credentials bound by the caller, the active proxy and
;; the repositories of a POM model, the last against the JVM's answers in
;; models/expected.edn.
;; Run: CLOJURE_CLI_ALLOW_HTTP_REPO=true ./bb -cp resources/src/babashka script/mvn_oracle/deps_mvn_test.clj
(ns deps-mvn-test
  (:require [babashka.deps.mvn :as mvn]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [clojure.tools.deps :as deps]
            [clojure.tools.deps.extensions :as ext]
            [clojure.tools.deps.extensions.maven]
            [clojure.tools.deps.extensions.pom :as pom]
            [clojure.tools.deps.util.session :as session]
            [org.httpkit.server :as server])
  (:import [java.util Base64]))

(def metadata
  "<metadata><groupId>acme</groupId><artifactId>lib</artifactId><versioning><versions><version>1.0.0</version></versions></versioning></metadata>")

(def lib-pom
  "<project><modelVersion>4.0.0</modelVersion><groupId>acme</groupId><artifactId>lib</artifactId><version>1.0.0</version></project>")

(defn- child-pom [declared-url]
  (str "<project><modelVersion>4.0.0</modelVersion>"
       "<parent><groupId>acme</groupId><artifactId>parent</artifactId><version>1</version></parent>"
       "<artifactId>child</artifactId><version>1</version>"
       "<repositories><repository><id>nexus</id><url>" declared-url "</url></repository></repositories>"
       "</project>"))

(def authorization
  (str "Basic " (.encodeToString (Base64/getEncoder) (.getBytes "user:secret" "UTF-8"))))

(defn- with-repository
  "Calls f with the URL of a repository under /repo/ that answers 401
  without user:secret, an atom of [path authorization] it saw, and a
  function returning a new local repository. /elsewhere/ answers 404."
  [f]
  (fs/with-temp-dir [dir {}]
    (let [seen (atom [])
          port (promise)
          stop (server/run-server
                (fn [{:keys [uri headers]}]
                  (let [auth (get headers "authorization")]
                    (swap! seen conj [uri auth])
                    (cond
                      (str/starts-with? uri "/elsewhere/") {:status 404}
                      (not= authorization auth) {:status 401 :headers {"WWW-Authenticate" "Basic"}}
                      (= "/repo/acme/lib/maven-metadata.xml" uri) {:status 200 :body metadata}
                      (= "/repo/acme/lib/1.0.0/lib-1.0.0.pom" uri) {:status 200 :body lib-pom}
                      (= "/repo/acme/lib/1.0.0/lib-1.0.0.jar" uri) {:status 200 :body "jar"}
                      (= "/repo/acme/child/1/child-1.pom" uri)
                      {:status 200 :body (child-pom (str "http://localhost:" @port "/elsewhere/"))}
                      :else {:status 404})))
                {:port 0 :legacy-return-value? false})]
      (deliver port (server/server-port stop))
      (try
        (f (str "http://localhost:" @port "/repo/") seen #(str (fs/file dir (str (gensym "local")))))
        (finally (server/server-stop! stop))))))

(defn- versions [url local]
  (mapv :mvn/version (ext/find-versions 'acme/lib nil :mvn {:mvn/repos {"nexus" {:url url}}
                                                            :mvn/local-repo local})))

(deftest credentials-test
  (with-repository
    (fn [url seen new-local]
      (let [credentials {"nexus" {:url url :username "user" :password "secret"}}]
        (binding [*err* (java.io.StringWriter.)]
          (session/with-session
            (testing "find-versions without credentials returns nothing"
              (is (= [] (versions url (new-local)))))

            (testing "with-repository-credentials applies the credentials of the repository"
              (is (= ["1.0.0"] (mvn/with-repository-credentials credentials (versions url (new-local))))))

            (testing "an inner form without credentials replaces the outer one"
              (is (= [] (mvn/with-repository-credentials credentials
                          (mvn/with-repository-credentials {} (versions url (new-local)))))))

            (testing "credentials for the same id at another URL do not apply"
              (is (= [] (mvn/with-repository-credentials
                          {"nexus" {:url "http://localhost:1/repo/" :username "user" :password "secret"}}
                          (versions url (new-local))))))

            (testing "a lookup with credentials after a lookup without returns the versions"
              (let [local (new-local)]
                (is (= [] (versions url local)))
                (is (= ["1.0.0"] (mvn/with-repository-credentials credentials (versions url local))))))

            (testing "with-repository-credentials ignores a repository without credentials"
              (is (= ["1.0.0"] (mvn/with-repository-credentials
                                 (assoc credentials "central" {:url "https://repo1.maven.org/maven2/"})
                                 (versions url (new-local))))))))
        (is (seq @seen))))))

(deftest declared-repository-test
  (with-repository
    (fn [url seen new-local]
      (binding [*err* (java.io.StringWriter.)]
        (session/with-session
          (mvn/with-repository-credentials {"nexus" {:url url :username "user" :password "secret"}}
            (try (ext/coord-deps 'acme/child {:mvn/version "1"} :mvn {:mvn/repos {"nexus" {:url url}}
                                                                      :mvn/local-repo (new-local)})
                 (catch Exception _)))))
      (let [elsewhere (filter #(str/starts-with? (first %) "/elsewhere/") @seen)]
        (testing "a POM-declared repository with the same id receives no Authorization header"
          (is (seq elsewhere))
          (is (not-any? second elsewhere)))))))

(deftest worker-threads-test
  (with-repository
    (fn [url _seen new-local]
      (testing "resolve-deps downloads with the bound credentials on its worker threads"
        (binding [*err* (java.io.StringWriter.)]
          (session/with-session
            (let [basis (mvn/with-repository-credentials {"nexus" {:url url :username "user" :password "secret"}}
                          (deps/resolve-deps {:deps {'acme/lib {:mvn/version "1.0.0"}}
                                              :mvn/repos {"nexus" {:url url}}
                                              :mvn/local-repo (new-local)}
                                             nil))]
              (is (= "1.0.0" (get-in basis ['acme/lib :mvn/version]))))))))))

(deftest invalid-credentials-test
  (testing "an entry with credentials and without :url throws without the password"
    (let [e (try (mvn/with-repository-credentials {"nexus" {:username "user" :password "secret"}} :body)
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (= {:repository "nexus" :key :url} (ex-data e)))
      (is (not (str/includes? (ex-message e) "secret")))))
  (testing "a :password without :username throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (mvn/with-repository-credentials {"nexus" {:url "https://x/" :password "secret"}} :body))))
  (testing "a non-string :password throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (mvn/with-repository-credentials {"nexus" {:url "https://x/" :username "u" :password :env/pass}} :body)))))

(deftest active-proxy-test
  (fs/with-temp-dir [dir {}]
    (let [real-home (System/getProperty "user.home")
          settings #(do (fs/create-dirs (fs/file dir ".m2"))
                        (spit (fs/file dir ".m2" "settings.xml") (str "<settings><proxies>" % "</proxies></settings>")))]
      (try
        (System/setProperty "user.home" (str dir))
        (testing "active-proxy returns nil without settings.xml"
          (is (nil? (mvn/active-proxy))))

        (settings (str "<proxy><id>off</id><active>false</active><host>off.example.com</host><port>1</port></proxy>"
                       "<proxy><id>on</id><protocol>https</protocol><host>proxy.example.com</host><port>8080</port>"
                       "<username>u</username><password>p</password><nonProxyHosts>localhost|*.example.org</nonProxyHosts></proxy>"
                       "<proxy><id>later</id><host>later.example.com</host><port>2</port></proxy>"))
        (testing "active-proxy returns the first active proxy"
          (is (= {:host "proxy.example.com" :port 8080 :protocol "https" :username "u" :password "p"
                  :non-proxy-hosts "localhost|*.example.org"}
                 (mvn/active-proxy))))

        (settings "<proxy><id>off</id><active>false</active><host>off.example.com</host><port>1</port></proxy>")
        (testing "active-proxy returns nil without an active proxy"
          (is (nil? (mvn/active-proxy))))
        (finally
          (System/setProperty "user.home" real-home))))))

(def models "script/mvn_oracle/models")

(def config {:mvn/repos {"central" {:url "https://repo1.maven.org/maven2/"}}})

(defn- model-data [file]
  (try (let [model (pom/read-model-file (fs/file models file) config)
             repos (mvn/model-repos model)]
         {:deps (vec (pom/model-deps model)) :repos repos :repo-order (vec (keys repos))})
       (catch Exception _ :throws)))

(deftest model-test
  (doseq [[file expected] (edn/read-string (slurp (fs/file models "expected.edn")))]
    (testing (str "read-model-file and model-repos on " file " match tools.deps on the JVM")
      (is (= expected (model-data file))))))

(let [{:keys [fail error]} (t/run-tests 'deps-mvn-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
