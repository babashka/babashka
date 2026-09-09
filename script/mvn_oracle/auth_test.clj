#!/usr/bin/env bb
;; End to end: a repository behind basic auth, the password encrypted in
;; settings.xml, resolved through babashka.deps/add-deps in this process.
;; Run: CLOJURE_CLI_ALLOW_HTTP_REPO=true ./bb -cp resources/src/babashka script/mvn_oracle/auth_test.clj
(ns auth-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :as t :refer [deftest is testing]]
            [org.httpkit.server :as server])
  (:import [java.util Base64]
           [java.util.zip ZipEntry ZipOutputStream]))

(def vectors (edn/read-string (slurp "script/mvn_oracle/cipher-vectors.edn")))
(def user "deployer")
(def case-1 (first (:cases vectors)))   ; plaintext "hunter2" and its blob

(defn- write-artifact!
  "auth-test/lib 1.0.0 under root, POM and a one-entry jar."
  [root]
  (let [dir (fs/file root "auth-test" "lib" "1.0.0")]
    (fs/create-dirs dir)
    (spit (fs/file dir "lib-1.0.0.pom")
          "<project><modelVersion>4.0.0</modelVersion><groupId>auth-test</groupId><artifactId>lib</artifactId><version>1.0.0</version></project>")
    (with-open [zip (ZipOutputStream. (io/output-stream (fs/file dir "lib-1.0.0.jar")))]
      (.putNextEntry zip (ZipEntry. "auth_test/lib.clj"))
      (.write zip (.getBytes "(ns auth-test.lib) (def answer 42)" "UTF-8"))
      (.closeEntry zip))))

(defn- expected-auth [password]
  (str "Basic " (.encodeToString (Base64/getEncoder) (.getBytes (str user ":" password) "UTF-8"))))

(defn- handler
  "Serves root, 401 without the right Authorization header. Records what
  it saw in seen."
  [root seen password]
  (fn [{:keys [uri headers]}]
    (let [auth (get headers "authorization")]
      (swap! seen conj auth)
      (if (not= auth (expected-auth password))
        {:status 401 :headers {"WWW-Authenticate" "Basic"} :body "no"}
        (let [f (fs/file root (subs uri 1))]
          (if (fs/exists? f)
            {:status 200 :body (fs/file f)}
            {:status 404 :body "missing"}))))))

(deftest encrypted-server-password-test
  (fs/with-temp-dir [dir {}]
    (let [root (fs/file dir "repo")
          m2 (fs/file dir ".m2")
          seen (atom [])
          stop (server/run-server (handler root seen (:plain case-1)) {:port 0 :legacy-return-value? false})
          port (server/server-port stop)]
      (try
        (write-artifact! root)
        (fs/create-dirs m2)
        (spit (fs/file m2 "settings-security.xml")
              (str "<settingsSecurity><master>" (:master-blob vectors) "</master></settingsSecurity>"))
        (spit (fs/file m2 "settings.xml")
              (str "<settings><servers><server><id>authrepo</id><username>" user
                   "</username><password>" (:blob case-1) "</password></server></servers></settings>"))
        ;; the settings namespace reads user.home at load, so set it first
        (System/setProperty "user.home" (str dir))
        (System/setProperty "settings.security" (str (fs/file m2 "settings-security.xml")))
        (require 'babashka.deps)
        ((resolve 'babashka.deps/add-deps)
         {:deps {'auth-test/lib {:mvn/version "1.0.0"}}
          :mvn/repos {"authrepo" {:url (str "http://localhost:" port "/")}}
          ;; not under dir: once required, the jar is open on the classpath,
          ;; and Windows cannot delete an open file. The process ends right
          ;; after the test, so nothing cleans this up.
          :mvn/local-repo (str (fs/create-temp-dir {:prefix "local-repo"}))}
         {:force true :extra-env {"BABASHKA_DEPS_RESOLVER" "bb"}})
        (require 'auth-test.lib)
        (is (= 42 @(resolve 'auth-test.lib/answer)))
        (testing "the server saw the decrypted password, never the blob"
          (is (seq @seen))
          (is (every? #{(expected-auth (:plain case-1))} @seen)))
        (finally
          (when (System/getenv "AUTH_TEST_DEBUG") (prn :seen @seen))
          (server/server-stop! stop))))))

(let [{:keys [fail error]} (t/run-tests 'auth-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
