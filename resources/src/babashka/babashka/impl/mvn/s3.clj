(ns babashka.impl.mvn.s3
  "Requests to s3:// repositories, signed with AWS Signature Version 4."
  {:no-doc true}
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.impl.mvn.env :as env]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.net URI URLEncoder]
           [java.security MessageDigest]
           [java.time Instant ZoneOffset ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def ^:private empty-sha256 "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

(defn s3-url?
  "Returns true if url starts with s3:."
  [url]
  (str/starts-with? url "s3:"))

(defn- hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn- sha256-hex [^String s]
  (hex (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))))

(defn- hmac ^bytes [^bytes key ^String data]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. key "HmacSHA256"))
    (.doFinal mac (.getBytes data "UTF-8"))))

(defn- encode-segment [^String s]
  (-> (URLEncoder/encode s "UTF-8")
      (str/replace "+" "%20")
      (str/replace "*" "%2A")
      (str/replace "%7E" "~")))

(defn encode-path
  "Returns path with each segment percent-encoded and the slashes kept."
  [path]
  (str/join "/" (map encode-segment (str/split path #"/" -1))))

(defn- amz-date []
  (.format (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'") (ZonedDateTime/now ZoneOffset/UTC)))

(defn signed-headers
  "Returns the headers that sign a request without body for credentials.
  path is the encoded request path, query the canonical query string or nil, headers are extra headers to sign.
  Omits the host header."
  [{:keys [method host path query region headers amz-date]}
   {:keys [access-key-id secret-access-key session-token]}]
  (let [date (subs amz-date 0 8)
        amz (cond-> {"x-amz-date" amz-date "x-amz-content-sha256" empty-sha256}
              session-token (assoc "x-amz-security-token" session-token))
        canonical-headers (into (sorted-map)
                                (map (fn [[k v]] [(str/lower-case k) (str/trim v)]))
                                (merge headers amz {"host" host}))
        signed (str/join ";" (keys canonical-headers))
        canonical (str/join "\n" [(str/upper-case (name method)) path (or query "")
                                  (apply str (map (fn [[k v]] (str k ":" v "\n")) canonical-headers))
                                  signed empty-sha256])
        scope (str date "/" region "/s3/aws4_request")
        to-sign (str/join "\n" ["AWS4-HMAC-SHA256" amz-date scope (sha256-hex canonical)])
        k (reduce hmac (.getBytes (str "AWS4" secret-access-key) "UTF-8") [date region "s3" "aws4_request"])]
    (assoc amz "Authorization"
           (str "AWS4-HMAC-SHA256 Credential=" access-key-id "/" scope
                ", SignedHeaders=" signed ", Signature=" (hex (hmac k to-sign))))))

(defn endpoint
  "Returns the :scheme and :host of S3 in region."
  [region]
  {:scheme "https"
   :host (str "s3." region (if (str/starts-with? region "cn-") ".amazonaws.com.cn" ".amazonaws.com"))})

(defn- signed-request
  "Returns an http request on key in bucket of region, signed with creds."
  [req bucket key query region creds]
  (let [{:keys [scheme host]} (endpoint region)
        path (str "/" (encode-segment bucket) (encode-path key))]
    (-> req
        (assoc :uri (str scheme "://" host path (when query (str "?" query))))
        (update :headers merge (signed-headers {:method (:method req) :host host :path path
                                                :query (when query (str query "="))
                                                :region region :amz-date (amz-date)}
                                               creds)))))

(def ^:private regions (atom {}))

(defn repo-url
  "Returns url without its query, and records the region in its region= parameter for the bucket."
  [url]
  (let [u (URI/create url)]
    (when-let [region (some->> (.getRawQuery u) (re-find #"(?:^|&)region=([^&]+)") second)]
      (swap! regions assoc (.getHost u) region))
    (str/replace url #"\?[^/]*" "")))

(defn- bucket-location
  "Returns the LocationConstraint of bucket, us-east-1 if empty, or nil if the request fails."
  [bucket creds client]
  (try
    (let [{:keys [status body]} (http/request (signed-request (cond-> {:method :get :throw false :timeout 120000}
                                                                client (assoc :client client))
                                                              bucket "" "location" "us-east-1" creds))]
      (when (= 200 status)
        (let [region (or (second (re-find #"<LocationConstraint[^>]*>([^<]*)</LocationConstraint>" body)) "")]
          (if (= "" region) "us-east-1" region))))
    (catch Exception _ nil)))

(defn- bucket-region
  "Returns the region of bucket.
  Uses the region= parameter of its repository URL first.
  Otherwise uses the location S3 reports, or us-east-1 if the request fails."
  [bucket creds client]
  (or (get @regions bucket)
      (let [region (or (bucket-location bucket creds client) "us-east-1")]
        (swap! regions assoc bucket region)
        region)))

(defn- valid [m]
  (when (and (not (str/blank? (:access-key-id m)))
             (not (str/blank? (:secret-access-key m))))
    m))

(defn- env-credentials []
  (valid {:access-key-id (env/getenv "AWS_ACCESS_KEY_ID")
          :secret-access-key (env/getenv "AWS_SECRET_ACCESS_KEY")
          :session-token (env/getenv "AWS_SESSION_TOKEN")}))

(defn- property-credentials []
  (valid {:access-key-id (System/getProperty "aws.accessKeyId")
          :secret-access-key (System/getProperty "aws.secretKey")
          :session-token (System/getProperty "aws.sessionToken")}))

(defn parse-profiles
  "Returns a map of profile name to a map of its keys and values in an AWS credentials file."
  [text]
  (first
   (reduce (fn [[profiles current] line]
             (let [line (str/trim line)]
               (cond
                 (or (str/blank? line) (str/starts-with? line "#") (str/starts-with? line ";"))
                 [profiles current]

                 (re-matches #"\[.*\]" line)
                 (let [p (-> (subs line 1 (dec (count line))) str/trim (str/replace #"^profile\s+" ""))]
                   [(update profiles p #(or % {})) p])

                 (and current (str/includes? line "="))
                 (let [i (str/index-of line "=")]
                   [(assoc-in profiles [current (str/trim (subs line 0 i))] (str/trim (subs line (inc i))))
                    current])

                 :else [profiles current])))
           [{} nil]
           (str/split-lines text))))

(defn- profile-credentials []
  (let [file (or (env/getenv "AWS_SHARED_CREDENTIALS_FILE")
                 (env/getenv "AWS_CREDENTIAL_PROFILES_FILE")
                 (str (fs/path (System/getProperty "user.home") ".aws" "credentials")))
        profile (or (env/getenv "AWS_PROFILE") (System/getProperty "aws.profile") "default")]
    (when (fs/exists? file)
      (let [p (get (parse-profiles (slurp file)) profile)]
        (valid {:access-key-id (get p "aws_access_key_id")
                :secret-access-key (get p "aws_secret_access_key")
                :session-token (get p "aws_session_token")})))))

(defn- json-credentials [body]
  (let [{:strs [AccessKeyId SecretAccessKey Token Expiration]} (json/parse-string body)]
    (some-> (valid {:access-key-id AccessKeyId :secret-access-key SecretAccessKey :session-token Token})
            (assoc :expiration (some-> Expiration Instant/parse)))))

(defn- get-ok [req]
  (try
    (let [{:keys [status body]} (http/request (merge {:method :get :throw false :timeout 1000
                                                       :client (http/client {:connect-timeout 1000})}
                                                      req))]
      (when (= 200 status) body))
    (catch Exception _ nil)))

(defn- in-container? []
  (or (env/getenv "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI")
      (env/getenv "AWS_CONTAINER_CREDENTIALS_FULL_URI")))

(defn- metadata-host []
  (or (System/getProperty "com.amazonaws.sdk.ec2MetadataServiceEndpointOverride")
      (env/getenv "AWS_EC2_METADATA_SERVICE_ENDPOINT")
      (when (in-container?) "http://169.254.170.2")
      "http://169.254.169.254"))

(defn- metadata-uri [path]
  (str (str/replace (metadata-host) #"/+$" "") path))

(defn- container-credentials []
  (when-let [uri (or (some-> (env/getenv "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI") metadata-uri)
                     (env/getenv "AWS_CONTAINER_CREDENTIALS_FULL_URI"))]
    (some-> (get-ok (cond-> {:uri uri}
                      (env/getenv "AWS_CONTAINER_AUTHORIZATION_TOKEN")
                      (assoc :headers {"Authorization" (env/getenv "AWS_CONTAINER_AUTHORIZATION_TOKEN")})))
            json-credentials)))

(defn- instance-credentials [token]
  (when-not (in-container?)
    (let [headers (if token {"X-aws-ec2-metadata-token" token} {})
          base (metadata-uri "/latest/meta-data/iam/security-credentials/")]
      (when-let [role (some-> (get-ok {:uri base :headers headers}) str/split-lines first str/trim not-empty)]
        (some-> (get-ok {:uri (str base role) :headers headers}) json-credentials)))))

(defn- imds-v2-credentials []
  (when-let [token (get-ok {:method :put :uri (metadata-uri "/latest/api/token")
                            :headers {"X-aws-ec2-metadata-token-ttl-seconds" "21600"}})]
    (instance-credentials token)))

(def ^:private cached-credentials (atom nil))

(defn- expired? [{:keys [expiration]}]
  (and expiration (.isBefore ^Instant expiration (.plusSeconds (Instant/now) 300))))

(defn default-credentials
  "Returns the AWS credentials from the environment, the aws. system properties, the credentials file, the container or the instance metadata service, in that order, or nil if none.
  Caches the result for the process until it expires."
  []
  (let [c @cached-credentials]
    (if (and c (not (expired? c)))
      (:credentials c)
      (let [creds (some #(%) [env-credentials property-credentials profile-credentials
                              container-credentials imds-v2-credentials #(instance-credentials nil)])]
        (when creds
          (reset! cached-credentials {:credentials creds :expiration (:expiration creds)}))
        creds))))

(defn- failure-message [method bucket]
  (if (= :head method)
    "Artifact not found"
    (str "Unexpected error downloading artifact from " bucket)))

(defn request
  "Returns the http request for a request on an s3:// url.
  Signs with the access key id and secret access key in :basic-auth, or the default credentials if absent.
  Throws if there are no credentials."
  [{:keys [uri method basic-auth client] :as req}]
  (let [u (URI/create uri)
        bucket (.getHost u)
        creds (if-let [[id secret] (when (every? some? basic-auth) basic-auth)]
                {:access-key-id id :secret-access-key secret}
                (default-credentials))]
    (when-not creds
      (throw (ex-info (failure-message method bucket) {:bucket bucket :reason :no-credentials})))
    (signed-request (dissoc req :basic-auth) bucket (.getPath u) nil (bucket-region bucket creds client) creds)))

(defn check-response
  "Returns response with status 404 for a 403.
  Throws for a status other than 200, 403 and 404."
  [method uri {:keys [status] :as response}]
  (cond
    (#{200 404} status) response
    (= 403 status) (assoc response :status 404)
    :else (do (when (instance? java.io.Closeable (:body response))
                (.close ^java.io.Closeable (:body response)))
              (throw (ex-info (failure-message method (.getHost (URI/create uri)))
                              {:uri uri :status status})))))
