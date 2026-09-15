#!/usr/bin/env bb
;; A POM's parent resolves from the repositories the POM declares, http: ones
;; included, as in the JVM tools.deps 0.31.1646.
;; Run: ./bb -cp resources/src/babashka script/mvn_oracle/pom_repositories_test.clj
(ns pom-repositories-test
  (:require [babashka.fs :as fs]
            [babashka.impl.mvn.env :as env]
            [clojure.test :as t :refer [deftest is testing]]
            [clojure.tools.deps.extensions :as ext]
            [clojure.tools.deps.extensions.maven]
            [clojure.tools.deps.util.session :as session]
            [org.httpkit.server :as server]))

(defn- pom [& body]
  (str "<project><modelVersion>4.0.0</modelVersion>" (apply str body) "</project>"))

(defn- publish! [root artifact text]
  (let [d (fs/file root "p" artifact "1")]
    (fs/create-dirs d)
    (spit (fs/file d (str artifact "-1.pom")) text)))

(deftest http-pom-repository-test
  (testing "a parent from an http: repository the POM declares resolves without CLOJURE_CLI_ALLOW_HTTP_REPO"
    (fs/with-temp-dir [dir {}]
      (let [http-root (fs/file dir "http-repo")
            file-root (fs/file dir "file-repo")
            seen (atom [])
            stop (server/run-server (fn [{:keys [uri]}]
                                      (swap! seen conj uri)
                                      (let [f (fs/file http-root (subs uri 1))]
                                        (if (fs/regular-file? f) {:status 200 :body (fs/file f)} {:status 404 :body "missing"})))
                                    {:port 0 :legacy-return-value? false})
            url (str "http://localhost:" (server/server-port stop) "/")
            real-home (System/getProperty "user.home")]
        (try
          (publish! http-root "parent"
                    (pom "<groupId>p</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging>"
                         "<dependencies><dependency><groupId>p</groupId><artifactId>leaf</artifactId><version>1</version></dependency></dependencies>"))
          (publish! file-root "child"
                    (pom "<parent><groupId>p</groupId><artifactId>parent</artifactId><version>1</version></parent>"
                         "<artifactId>child</artifactId>"
                         "<repositories><repository><id>declared</id><url>" url "</url></repository></repositories>"))
          ;; no settings.xml mirror may redirect the declared repository
          (System/setProperty "user.home" (str dir))
          (with-redefs [env/getenv (fn [name] (when-not (= "CLOJURE_CLI_ALLOW_HTTP_REPO" name) (System/getenv name)))]
            (binding [*err* (java.io.StringWriter.)]
              (session/with-session
                (is (= '[[p/leaf {:mvn/version "1"}]]
                       (ext/coord-deps 'p/child {:mvn/version "1"} :mvn
                                       {:mvn/repos {"local" {:url (str (.toURI (fs/file file-root)))}}
                                        :mvn/local-repo (str (fs/file dir "local"))}))))))
          (is (some #{"/p/parent/1/parent-1.pom"} @seen))
          (finally
            (System/setProperty "user.home" real-home)
            (server/server-stop! stop)))))))

(let [{:keys [fail error]} (t/run-tests 'pom-repositories-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
