#!/usr/bin/env bb
;; s3:// repositories. Run: ./bb -cp resources/src/babashka script/mvn_oracle/s3_test.clj
(ns s3-test
  (:require [babashka.fs :as fs]
            [babashka.impl.mvn.env :as env]
            [babashka.impl.mvn.s3 :as s3]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [clojure.tools.deps.extensions :as ext]
            [clojure.tools.deps.extensions.maven]
            [clojure.tools.deps.util.session :as session]
            [org.httpkit.server :as server])
  (:import [java.util.zip ZipEntry ZipOutputStream]))

(deftest signature-test
  (testing "signed-headers matches the GET object example of the AWS Signature Version 4 documentation"
    (is (= (str "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41")
           (get (s3/signed-headers {:method :get :host "examplebucket.s3.amazonaws.com" :path "/test.txt"
                                    :region "us-east-1" :headers {"Range" "bytes=0-9"}
                                    :amz-date "20130524T000000Z"}
                                   {:access-key-id "AKIAIOSFODNN7EXAMPLE"
                                    :secret-access-key "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"})
                "Authorization")))))

(deftest encode-path-test
  (is (= "/a%20b/c~d/e%2Bf" (s3/encode-path "/a b/c~d/e+f"))))

(deftest repo-url-test
  (is (= "s3://bucket/maven/releases" (s3/repo-url "s3://bucket/maven/releases?region=eu-west-1")))
  (is (= "s3://bucket/maven/" (s3/repo-url "s3://bucket/maven/"))))

(deftest parse-profiles-test
  (is (= {"default" {"aws_access_key_id" "a" "aws_secret_access_key" "b"}
          "ci" {"aws_access_key_id" "c"}}
         (s3/parse-profiles "# comment\n[default]\naws_access_key_id = a\naws_secret_access_key=b\n\n[profile ci]\n; x\naws_access_key_id = c\n"))))

(def access-key "test-key")
(def secret-key "test-secret")

(defn- write-artifact! [root]
  (let [dir (fs/file root "bucket" "maven" "s3-test" "lib" "1.0.0")]
    (fs/create-dirs dir)
    (spit (fs/file dir "lib-1.0.0.pom")
          "<project><modelVersion>4.0.0</modelVersion><groupId>s3-test</groupId><artifactId>lib</artifactId><version>1.0.0</version></project>")
    (with-open [zip (ZipOutputStream. (io/output-stream (fs/file dir "lib-1.0.0.jar")))]
      (.putNextEntry zip (ZipEntry. "s3_test/lib.clj"))
      (.write zip (.getBytes "(ns s3-test.lib) (def answer 42)" "UTF-8"))
      (.closeEntry zip))))

(defn- valid-signature? [{:keys [request-method uri query-string headers]} creds region]
  (when (get headers "x-amz-date")
    (let [expected (s3/signed-headers {:method request-method :host (get headers "host") :path uri
                                       :query (some-> query-string (str "="))
                                       :region region :amz-date (get headers "x-amz-date")}
                                      creds)]
      (= (get expected "Authorization") (get headers "authorization")))))

(defn- handler
  "Serves root path-style, 403 for a request not signed with creds for region.
  Answers a location request signed for us-east-1 with location, or 403 if location is nil.
  Serves creds as container and instance credentials."
  [root {:keys [creds region location]} seen]
  (fn [{:keys [request-method uri query-string headers] :as req}]
    (let [creds-json (json/generate-string {"AccessKeyId" (:access-key-id creds)
                                            "SecretAccessKey" (:secret-access-key creds)
                                            "Token" (:session-token creds)
                                            "Expiration" "2999-01-01T00:00:00Z"})
          token-ok? (= (:session-token creds) (get headers "x-amz-security-token"))]
      (cond
        (= "/container" uri)
        (if (= "container-token" (get headers "authorization"))
          {:status 200 :body creds-json}
          {:status 401})

        (and (= :put request-method) (= "/latest/api/token" uri)) {:status 200 :body "imds-token"}
        (= "/latest/meta-data/iam/security-credentials/" uri) {:status 200 :body "role"}
        (= "/latest/meta-data/iam/security-credentials/role" uri)
        (if (= "imds-token" (get headers "x-aws-ec2-metadata-token"))
          {:status 200 :body creds-json}
          {:status 401})

        (str/starts-with? uri "/no-metadata") {:status 404}

        (= "location" query-string)
        (do (swap! seen conj {:location true})
            (if (and location (valid-signature? req creds "us-east-1") token-ok?)
              {:status 200
               :body (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                          "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                          location "</LocationConstraint>")}
              {:status 403 :body "denied"}))

        :else
        (do (swap! seen conj (select-keys headers ["authorization" "x-amz-security-token"]))
            (if (and (valid-signature? req creds region) token-ok?)
              (let [f (fs/file root (subs uri 1))]
                (if (fs/regular-file? f) {:status 200 :body (fs/file f)} {:status 404 :body "missing"}))
              {:status 403 :body "denied"}))))))

(defn- resolve-lib
  "Resolves s3-test/lib from an s3:// repository url on a server for creds.
  Returns :paths, or :error with the exception message, and the :seen
  requests."
  [server-opts env & {:keys [settings url] :or {url "s3://bucket/maven"}}]
  (fs/with-temp-dir [dir {}]
    (let [root (fs/file dir "s3")
          seen (atom [])
          stop (server/run-server (handler root (merge {:region "us-east-1" :location ""} server-opts) seen)
                                  {:port 0 :legacy-return-value? false})
          port (server/server-port stop)
          real-home (System/getProperty "user.home")
          env (merge {"AWS_SHARED_CREDENTIALS_FILE" (str (fs/file dir "no-credentials"))
                      "AWS_EC2_METADATA_SERVICE_ENDPOINT" (str "http://localhost:" port "/no-metadata")}
                     (update-vals env #(str/replace % "PORT" (str port))))
          config {:mvn/repos {"private" {:url url}}
                  :mvn/local-repo (str (fs/file dir "local-repo"))}]
      (reset! @#'s3/cached-credentials nil)
      (reset! @#'s3/regions {})
      (try
        (write-artifact! root)
        (fs/create-dirs (fs/file dir ".m2"))
        (when settings (spit (fs/file dir ".m2" "settings.xml") settings))
        (System/setProperty "user.home" (str dir))
        (with-redefs [env/getenv #(get env %)
                      s3/endpoint (fn [_] {:scheme "http" :host (str "localhost:" port)})]
          (session/with-session
            {:paths (filterv fs/exists? (ext/coord-paths 's3-test/lib {:mvn/version "1.0.0"} :mvn config))
             :seen @seen}))
        (catch Exception e {:error (ex-message e) :seen @seen})
        (finally
          (System/setProperty "user.home" real-home)
          (server/server-stop! stop))))))

(def creds {:access-key-id access-key :secret-access-key secret-key})

(def creds-env {"AWS_ACCESS_KEY_ID" access-key "AWS_SECRET_ACCESS_KEY" secret-key})

(defn- jar? [{:keys [paths]}]
  (boolean (some #(str/ends-with? % "lib-1.0.0.jar") paths)))

(deftest credentials-test
  (testing "AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY sign the requests"
    (is (jar? (resolve-lib {:creds creds} creds-env))))
  (testing "AWS_SESSION_TOKEN is sent as x-amz-security-token"
    (let [{:keys [seen] :as result} (resolve-lib {:creds (assoc creds :session-token "session")}
                                                 (assoc creds-env "AWS_SESSION_TOKEN" "session"))]
      (is (jar? result))
      (is (every? #(= "session" (get % "x-amz-security-token")) (remove :location seen)))))
  (testing "a settings.xml server signs the requests to its repository"
    (is (jar? (resolve-lib {:creds creds} {}
                           :settings (str "<settings><servers><server><id>private</id><username>" access-key
                                          "</username><password>" secret-key "</password></server></servers></settings>")))))
  (testing "AWS_PROFILE selects a profile of the credentials file"
    (fs/with-temp-dir [dir {}]
      (let [f (fs/file dir "credentials")]
        (spit f (str "[default]\naws_access_key_id = wrong\naws_secret_access_key = wrong\n"
                     "[ci]\naws_access_key_id = " access-key "\naws_secret_access_key = " secret-key "\n"))
        (is (jar? (resolve-lib {:creds creds} {"AWS_SHARED_CREDENTIALS_FILE" (str f) "AWS_PROFILE" "ci"}))))))
  (testing "AWS_CONTAINER_CREDENTIALS_FULL_URI supplies container credentials"
    (is (jar? (resolve-lib {:creds (assoc creds :session-token "c")}
                           {"AWS_CONTAINER_CREDENTIALS_FULL_URI" "http://localhost:PORT/container"
                            "AWS_CONTAINER_AUTHORIZATION_TOKEN" "container-token"}))))
  (testing "the instance metadata service supplies credentials with an IMDSv2 token"
    (is (jar? (resolve-lib {:creds (assoc creds :session-token "i")}
                           {"AWS_EC2_METADATA_SERVICE_ENDPOINT" "http://localhost:PORT"}))))
  (testing "a wrong secret leaves the artifact unresolved"
    (is (re-find #"Could not find artifact s3-test:lib:jar:1.0.0"
                 (:error (resolve-lib {:creds creds} (assoc creds-env "AWS_SECRET_ACCESS_KEY" "wrong"))))))
  (testing "no credentials fail the download without a request"
    (let [{:keys [seen error]} (resolve-lib {:creds creds} {})]
      (is (str/ends-with? error "Unexpected error downloading artifact from bucket"))
      (is (empty? seen)))))

(deftest region-test
  (testing "an empty LocationConstraint selects us-east-1"
    (is (jar? (resolve-lib {:creds creds} creds-env))))
  (testing "the LocationConstraint of the bucket selects the signing region"
    (is (jar? (resolve-lib {:creds creds :region "eu-west-1" :location "eu-west-1"} creds-env))))
  (testing "the region= parameter skips the location request"
    (let [{:keys [seen] :as result} (resolve-lib {:creds creds :region "ap-south-1" :location "eu-west-1"} creds-env
                                                 :url "s3://bucket/maven?region=ap-south-1")]
      (is (jar? result))
      (is (not-any? :location seen))))
  (testing "a failed location request selects us-east-1"
    (is (jar? (resolve-lib {:creds creds :location nil} creds-env)))))

(defn -main []
  (let [{:keys [fail error]} (t/run-tests 's3-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
