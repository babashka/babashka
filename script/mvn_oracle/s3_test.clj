#!/usr/bin/env bb
;; s3:// repositories. Run: ./bb -cp resources/src/babashka script/mvn_oracle/s3_test.clj
(ns s3-test
  (:require [babashka.fs :as fs]
            [babashka.impl.mvn.env :as env]
            [babashka.impl.mvn.repo :as repo]
            [babashka.impl.mvn.s3 :as s3]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [clojure.tools.deps :as deps]
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
                "Authorization"))))
  (testing "signed-headers matches the GET bucket lifecycle example"
    (is (str/ends-with? (get (s3/signed-headers {:method :get :host "examplebucket.s3.amazonaws.com" :path "/"
                                                 :query "lifecycle=" :region "us-east-1" :amz-date "20130524T000000Z"}
                                                {:access-key-id "AKIAIOSFODNN7EXAMPLE"
                                                 :secret-access-key "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"})
                             "Authorization")
                        "Signature=fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543")))
  (testing "signed-headers matches the GET bucket list objects example"
    (is (str/ends-with? (get (s3/signed-headers {:method :get :host "examplebucket.s3.amazonaws.com" :path "/"
                                                 :query "max-keys=2&prefix=J" :region "us-east-1" :amz-date "20130524T000000Z"}
                                                {:access-key-id "AKIAIOSFODNN7EXAMPLE"
                                                 :secret-access-key "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"})
                             "Authorization")
                        "Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7"))))

(deftest encode-path-test
  (is (= "/a%20b/c~d/e%2Bf" (s3/encode-path "/a b/c~d/e+f"))))

(deftest repo-url-test
  (is (= "s3://bucket/maven/releases" (s3/repo-url "s3://bucket/maven/releases?region=eu-west-1")))
  (is (= "s3://bucket/maven" (s3/repo-url "s3://bucket/maven/")))
  (is (= "s3://bucket/maven/releases" (s3/repo-url "s3://bucket//maven//releases/")))
  (is (= "s3://bucket/" (s3/repo-url "s3://bucket"))))

(deftest region-parameter-test
  (is (= "eu-west-1" (s3/region "s3://bucket/maven?region=eu-west-1")))
  (is (= "eu-west-1" (s3/region "s3://bucket/maven?x=1&region=eu-west-1")))
  (is (nil? (s3/region "s3://bucket/maven"))))

(deftest remote-repo-test
  (testing "remote-repo returns the region= parameter as :region of its repository only"
    (is (= {:url "s3://bucket/maven/" :display-url "s3://bucket//maven?region=eu-west-1" :region "eu-west-1"}
           (select-keys (repo/remote-repo {} ["a" {:url "s3://bucket//maven?region=eu-west-1"}])
                        [:url :display-url :region])))
    (is (nil? (:region (repo/remote-repo {} ["b" {:url "s3://bucket/maven"}]))))))

(deftest parse-profiles-test
  (is (= {"default" {"aws_access_key_id" "a" "aws_secret_access_key" "b"}
          "ci" {"aws_access_key_id" "c"}}
         (s3/parse-profiles "# comment\n[default]\naws_access_key_id = a\naws_secret_access_key=b\n\n[profile ci]\n; x\naws_access_key_id = c\n"))))

(def access-key "test-key")
(def secret-key "test-secret")

(defn- pom [artifact version & more]
  (str "<project><modelVersion>4.0.0</modelVersion><groupId>s3-test</groupId><artifactId>" artifact
       "</artifactId><version>" version "</version>" (apply str more) "</project>"))

(defn- write-jar! [file]
  (with-open [zip (ZipOutputStream. (io/output-stream file))]
    (.putNextEntry zip (ZipEntry. "s3_test/lib.clj"))
    (.write zip (.getBytes "(ns s3-test.lib) (def answer 42)" "UTF-8"))
    (.closeEntry zip)))

(defn- write-bucket!
  "Writes s3-test/lib 1.0.0 with its maven-metadata.xml, s3-test/app 1.0.0
  with parent s3-test/parent and a dependency on s3-test/lib, and
  s3-test/snap 1.0-SNAPSHOT under root."
  [root]
  (let [group (fs/file root "bucket" "maven" "s3-test")
        file (fn [& parts] (let [f (apply fs/file group parts)] (fs/create-dirs (fs/parent f)) f))]
    (spit (file "lib" "1.0.0" "lib-1.0.0.pom") (pom "lib" "1.0.0"))
    (write-jar! (file "lib" "1.0.0" "lib-1.0.0.jar"))
    (spit (file "lib" "maven-metadata.xml")
          (str "<metadata><groupId>s3-test</groupId><artifactId>lib</artifactId><versioning>"
               "<latest>1.0.0</latest><release>1.0.0</release><versions><version>1.0.0</version></versions>"
               "</versioning></metadata>"))
    (spit (file "parent" "1.0.0" "parent-1.0.0.pom")
          (pom "parent" "1.0.0" "<packaging>pom</packaging>"
               "<dependencies><dependency><groupId>s3-test</groupId><artifactId>lib</artifactId>"
               "<version>1.0.0</version></dependency></dependencies>"))
    (spit (file "app" "1.0.0" "app-1.0.0.pom")
          (str "<project><modelVersion>4.0.0</modelVersion>"
               "<parent><groupId>s3-test</groupId><artifactId>parent</artifactId><version>1.0.0</version></parent>"
               "<artifactId>app</artifactId></project>"))
    (write-jar! (file "app" "1.0.0" "app-1.0.0.jar"))
    (spit (file "snap" "1.0-SNAPSHOT" "maven-metadata.xml")
          (str "<metadata><groupId>s3-test</groupId><artifactId>snap</artifactId><version>1.0-SNAPSHOT</version>"
               "<versioning><snapshot><timestamp>20260101.120000</timestamp><buildNumber>1</buildNumber></snapshot>"
               "<lastUpdated>20260101120000</lastUpdated></versioning></metadata>"))
    (spit (file "snap" "1.0-SNAPSHOT" "snap-1.0-20260101.120000-1.pom") (pom "snap" "1.0-SNAPSHOT"))
    (write-jar! (file "snap" "1.0-SNAPSHOT" "snap-1.0-20260101.120000-1.jar"))))

(defn- valid-signature? [{:keys [request-method uri query-string headers]} creds region]
  (when (get headers "x-amz-date")
    (let [expected (s3/signed-headers {:method request-method :host (get headers "host") :path uri
                                       :query (some-> query-string (str "="))
                                       :region region :amz-date (get headers "x-amz-date")}
                                      creds)]
      (= (get expected "Authorization") (get headers "authorization")))))

(defn- handler
  "Serves root path-style, denied-status for a request not signed with creds for region.
  Answers missing-status for a missing file.
  Answers a location request signed for us-east-1 with location, or 403 if location is nil.
  Serves creds as container and instance credentials."
  [root {:keys [creds region location denied-status missing-status] :or {denied-status 403 missing-status 404}} seen]
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
                (if (fs/regular-file? f) {:status 200 :body (fs/file f)} {:status missing-status :body "missing"}))
              {:status denied-status :body "denied"}))))))

(defn- resolve-lib
  "Resolves deps from an s3:// repository url on a server for creds.
  deps is s3-test/lib 1.0.0 if absent.
  Returns :paths of all resolved libs and their :versions, or :error with
  the exception message, and the :seen requests."
  [server-opts env & {:keys [settings url deps]
                      :or {url "s3://bucket/maven" deps {'s3-test/lib {:mvn/version "1.0.0"}}}}]
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
        (write-bucket! root)
        (fs/create-dirs (fs/file dir ".m2"))
        (when settings (spit (fs/file dir ".m2" "settings.xml") settings))
        (System/setProperty "user.home" (str dir))
        (with-redefs [env/getenv #(get env %)
                      s3/endpoint (fn [_] {:scheme "http" :host (str "localhost:" port)})]
          (session/with-session
            (let [libs (deps/resolve-deps (assoc config :deps deps) nil)]
              {:paths (filterv fs/exists? (mapcat :paths (vals libs)))
               :versions (update-vals libs :mvn/version)
               :seen @seen})))
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
    (is (jar? (resolve-lib {:creds creds :location nil} creds-env))))
  (testing "HTTP 301 for the wrong region fails the download without a redirect"
    (is (str/ends-with? (:error (resolve-lib {:creds creds :region "eu-west-1" :location nil :denied-status 301} creds-env))
                        "Unexpected error downloading artifact from bucket"))))

(deftest metadata-test
  (testing "1.0-SNAPSHOT resolves to the timestamped jar of its maven-metadata.xml"
    (let [{:keys [paths]} (resolve-lib {:creds creds} creds-env :deps {'s3-test/snap {:mvn/version "1.0-SNAPSHOT"}})]
      (is (= ["snap-1.0-SNAPSHOT.jar"] (map fs/file-name paths)))))
  (testing "a version range and RELEASE resolve from maven-metadata.xml"
    (is (= {'s3-test/lib "1.0.0"} (:versions (resolve-lib {:creds creds} creds-env :deps {'s3-test/lib {:mvn/version "[1.0,2.0)"}}))))
    (is (= {'s3-test/lib "1.0.0"} (:versions (resolve-lib {:creds creds} creds-env :deps {'s3-test/lib {:mvn/version "RELEASE"}})))))
  (testing "the region= parameter signs the maven-metadata.xml request"
    (is (= {'s3-test/lib "1.0.0"}
           (:versions (resolve-lib {:creds creds :region "ap-south-1" :location "eu-west-1"} creds-env
                                   :url "s3://bucket/maven?region=ap-south-1"
                                   :deps {'s3-test/lib {:mvn/version "[1.0,2.0)"}}))))))

(deftest transitive-test
  (testing "a parent POM in the bucket adds s3-test/lib to s3-test/app"
    (is (= {'s3-test/app "1.0.0" 's3-test/lib "1.0.0"}
           (:versions (resolve-lib {:creds creds} creds-env :deps {'s3-test/app {:mvn/version "1.0.0"}}))))))

(deftest forbidden-test
  (testing "HTTP 403 for a missing key counts as absent"
    (is (= {'s3-test/lib "1.0.0"}
           (:versions (resolve-lib {:creds creds :missing-status 403} creds-env :deps {'s3-test/lib {:mvn/version "[1.0,2.0)"}}))))
    (is (re-find #"Could not find artifact s3-test:nope:jar:1.0.0"
                 (:error (resolve-lib {:creds creds :missing-status 403} creds-env :deps {'s3-test/nope {:mvn/version "1.0.0"}}))))))

(defn -main []
  (let [{:keys [fail error]} (t/run-tests 's3-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
