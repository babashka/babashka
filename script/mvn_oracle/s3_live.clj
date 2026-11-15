#!/usr/bin/env bb
;; Resolves a lib from a real s3:// repository with each AWS credentials source. Run: bb script/mvn_oracle/s3_live.clj <s3-url> <lib> <version> <settings.xml server id>
(require '[babashka.fs :as fs]
         '[babashka.http-client :as http]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[org.httpkit.server :as server])
(import '[java.security MessageDigest]
        '[javax.crypto Mac]
        '[javax.crypto.spec SecretKeySpec]
        '[java.time ZonedDateTime ZoneOffset]
        '[java.time.format DateTimeFormatter])

(def usage "Usage: bb script/mvn_oracle/s3_live.clj <s3-url> <lib> <version> <settings.xml server id>")

(when-not (= 4 (count *command-line-args*))
  (println usage)
  (System/exit 1))

(def bucket-url (nth *command-line-args* 0))
(def lib (symbol (nth *command-line-args* 1)))
(def version (nth *command-line-args* 2))
(def server-id (nth *command-line-args* 3))
(def bb (str (fs/absolutize (if (fs/windows?) "bb.exe" "bb"))))

(defn- tag [s t]
  (some-> (re-find (re-pattern (str "<" t ">\\s*([^<]*?)\\s*</" t ">")) s) second))

(def server-block
  (let [f (fs/path (System/getProperty "user.home") ".m2" "settings.xml")]
    (when (fs/exists? f)
      (some #(when (= server-id (tag % "id")) %) (str/split (slurp (str f)) #"</server>")))))

(def static-id (some-> server-block (tag "username")))
(def static-secret (some-> server-block (tag "password")))

(when (or (str/blank? static-id) (str/blank? static-secret) (str/starts-with? static-secret "{"))
  (println "Add a <server> with id" server-id "and a plain username and password to ~/.m2/settings.xml")
  (System/exit 1))

(defn- hex [^bytes bs] (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn- sha256-hex [^String s]
  (hex (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))))

(defn- hmac [^bytes k ^String d]
  (let [m (Mac/getInstance "HmacSHA256")]
    (.init m (SecretKeySpec. k "HmacSHA256"))
    (.doFinal m (.getBytes d "UTF-8"))))

(defn- session-credentials
  "Returns temporary credentials from STS GetSessionToken, signed with the static key."
  []
  (let [amz (.format (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'") (ZonedDateTime/now ZoneOffset/UTC))
        date (subs amz 0 8)
        host "sts.amazonaws.com"
        query "Action=GetSessionToken&DurationSeconds=900&Version=2011-06-15"
        canonical (str/join "\n" ["GET" "/" query (str "host:" host "\nx-amz-date:" amz "\n")
                                  "host;x-amz-date" (sha256-hex "")])
        scope (str date "/us-east-1/sts/aws4_request")
        to-sign (str/join "\n" ["AWS4-HMAC-SHA256" amz scope (sha256-hex canonical)])
        k (reduce hmac (.getBytes (str "AWS4" static-secret) "UTF-8") [date "us-east-1" "sts" "aws4_request"])
        {:keys [status body]} (http/get (str "https://" host "/?" query)
                                        {:throw false
                                         :headers {"x-amz-date" amz
                                                   "Authorization" (str "AWS4-HMAC-SHA256 Credential=" static-id "/" scope
                                                                        ", SignedHeaders=host;x-amz-date, Signature="
                                                                        (hex (hmac k to-sign)))}})]
    (when-not (= 200 status)
      (println "GetSessionToken failed with HTTP" status)
      (System/exit 1))
    {:id (tag body "AccessKeyId") :secret (tag body "SecretAccessKey")
     :token (tag body "SessionToken") :expiration (tag body "Expiration")}))

(def sess (session-credentials))

(def secrets (remove str/blank? [static-id static-secret (:id sess) (:secret sess) (:token sess)]))

(defn- redact [s] (reduce #(str/replace %1 %2 "***") s secrets))

(def creds-json (json/generate-string {"AccessKeyId" (:id sess) "SecretAccessKey" (:secret sess)
                                       "Token" (:token sess) "Expiration" (:expiration sess)}))

(def stop
  (server/run-server
   (fn [{:keys [request-method uri headers]}]
     (let [imds? (= "imds-token" (get headers "x-aws-ec2-metadata-token"))]
       (cond
         (= "/container" uri) (if (= "container-token" (get headers "authorization")) {:status 200 :body creds-json} {:status 401})
         (and (= :put request-method) (= "/latest/api/token" uri)) {:status 200 :body "imds-token"}
         (= "/latest/meta-data/iam/security-credentials/" uri) (if imds? {:status 200 :body "role"} {:status 401})
         (= "/latest/meta-data/iam/security-credentials/role" uri) (if imds? {:status 200 :body creds-json} {:status 401})
         :else {:status 404})))
   {:port 0 :legacy-return-value? false}))

(def local (str "http://localhost:" (server/server-port stop)))

(defn- child-code [home m2]
  (pr-str
   `(do (System/setProperty "user.home" ~home)
        (when-let [id# (System/getenv "S3_LIVE_PROPERTY_ID")]
          (System/setProperty "aws.accessKeyId" id#)
          (System/setProperty "aws.secretKey" (System/getenv "S3_LIVE_PROPERTY_SECRET")))
        (require '[clojure.tools.deps])
        (try (let [basis# ((resolve 'clojure.tools.deps/create-basis)
                           {:user nil
                            :project {:deps {'~lib {:mvn/version ~version}}
                                      :mvn/repos {"s3" {:url ~bucket-url} "clojars" nil}
                                      :mvn/local-repo ~m2}})]
               (println "RESULT OK" (get-in basis# [:libs '~lib :mvn/version])))
             (catch Exception e# (println "RESULT ERR" (ex-message e#)))))))

(defn- run-case [label env expect-ok?]
  (fs/with-temp-dir [dir {}]
    (let [home (str (fs/path dir "home"))
          m2 (str (fs/path dir "m2"))
          _ (fs/create-dirs home)
          env (update-vals env #(if (fn? %) (% dir) %))
          {:keys [out]} (p/shell {:out :string :err :out :continue true
                                  :extra-env (merge {"BABASHKA_DEPS_RESOLVER" "bb"
                                                     "AWS_EC2_METADATA_SERVICE_ENDPOINT" (str local "/none")}
                                                    env)}
                                 bb "-cp" "resources/src/babashka" "-e" (child-code home m2))
          result (or (some #(when (str/starts-with? % "RESULT") %) (str/split-lines out)) "RESULT none")
          ok? (str/starts-with? result "RESULT OK")]
      (println (format "%-4s %-28s %s" (if (= ok? expect-ok?) "PASS" "FAIL") label (redact result)))
      (= ok? expect-ok?))))

(def session-env {"AWS_ACCESS_KEY_ID" (:id sess) "AWS_SECRET_ACCESS_KEY" (:secret sess)
                  "AWS_SESSION_TOKEN" (:token sess)})

(defn- tamper [s]
  (let [i (quot (count s) 2)]
    (str (subs s 0 i) (if (= \A (.charAt ^String s i)) "B" "A") (subs s (inc i)))))

(def results
  [(run-case "no credentials" {} false)
   (run-case "environment" {"AWS_ACCESS_KEY_ID" static-id "AWS_SECRET_ACCESS_KEY" static-secret} true)
   (run-case "system properties" {"S3_LIVE_PROPERTY_ID" static-id "S3_LIVE_PROPERTY_SECRET" static-secret} true)
   (run-case "AWS_PROFILE"
             {"AWS_PROFILE" "live"
              "AWS_SHARED_CREDENTIALS_FILE"
              (fn [dir]
                (let [f (str (fs/path dir "credentials"))]
                  (spit f (str "[default]\naws_access_key_id = wrong\naws_secret_access_key = wrong\n"
                               "[live]\naws_access_key_id = " static-id "\naws_secret_access_key = " static-secret "\n"))
                  f))}
             true)
   (run-case "session token" session-env true)
   (run-case "session token missing" (dissoc session-env "AWS_SESSION_TOKEN") false)
   (run-case "session token tampered" (update session-env "AWS_SESSION_TOKEN" tamper) false)
   (run-case "container" {"AWS_CONTAINER_CREDENTIALS_FULL_URI" (str local "/container")
                          "AWS_CONTAINER_AUTHORIZATION_TOKEN" "container-token"}
             true)
   (run-case "instance metadata" {"AWS_EC2_METADATA_SERVICE_ENDPOINT" local} true)])

(server/server-stop! stop)
(System/exit (if (every? true? results) 0 1))
