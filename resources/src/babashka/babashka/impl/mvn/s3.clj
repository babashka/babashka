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
  path is the encoded request path, headers are extra headers to sign.
  Omits the host header."
  [{:keys [method host path region headers amz-date]}
   {:keys [access-key-id secret-access-key session-token]}]
  (let [date (subs amz-date 0 8)
        amz (cond-> {"x-amz-date" amz-date "x-amz-content-sha256" empty-sha256}
              session-token (assoc "x-amz-security-token" session-token))
        canonical-headers (into (sorted-map)
                                (map (fn [[k v]] [(str/lower-case k) (str/trim v)]))
                                (merge headers amz {"host" host}))
        signed (str/join ";" (keys canonical-headers))
        canonical (str/join "\n" [(str/upper-case (name method)) path ""
                                  (apply str (map (fn [[k v]] (str k ":" v "\n")) canonical-headers))
                                  signed empty-sha256])
        scope (str date "/" region "/s3/aws4_request")
        to-sign (str/join "\n" ["AWS4-HMAC-SHA256" amz-date scope (sha256-hex canonical)])
        k (reduce hmac (.getBytes (str "AWS4" secret-access-key) "UTF-8") [date region "s3" "aws4_request"])]
    (assoc amz "Authorization"
           (str "AWS4-HMAC-SHA256 Credential=" access-key-id "/" scope
                ", SignedHeaders=" signed ", Signature=" (hex (hmac k to-sign))))))

(def ^:private regions (atom {}))

(defn repo-url
  "Returns url without its query, and records the region in its region= parameter for the bucket."
  [url]
  (let [u (URI/create url)]
    (when-let [region (some->> (.getRawQuery u) (re-find #"(?:^|&)region=([^&]+)") second)]
      (swap! regions assoc (.getHost u) region))
    (str/replace url #"\?[^/]*" "")))

(defn- endpoint-override []
  (some-> (or (env/getenv "AWS_ENDPOINT_URL_S3") (env/getenv "AWS_ENDPOINT_URL"))
          (str/replace #"/+$" "")))

(defn- endpoint
  "Returns :scheme, :host and :prefix, the encoded path before the key, of bucket in region."
  [bucket region]
  (if-let [e (endpoint-override)]
    (let [u (URI/create e)]
      {:scheme (.getScheme u) :host (.getRawAuthority u)
       :prefix (str (.getRawPath u) "/" (encode-segment bucket))})
    (if (str/includes? bucket ".")
      {:scheme "https" :host (str "s3." region ".amazonaws.com") :prefix (str "/" (encode-segment bucket))}
      {:scheme "https" :host (str bucket ".s3." region ".amazonaws.com") :prefix ""})))

(defn- discover-region
  "Returns the x-amz-bucket-region header of a HEAD request on bucket, or nil if absent."
  [bucket client]
  (let [url (if (str/includes? bucket ".")
              (str "https://s3.amazonaws.com/" (encode-segment bucket) "/")
              (str "https://" bucket ".s3.amazonaws.com/"))]
    (try
      (-> (http/request (cond-> {:method :head :uri url :throw false :timeout 10000}
                          client (assoc :client client)))
          (get-in [:headers "x-amz-bucket-region"]))
      (catch Exception _ nil))))

(defn- bucket-region
  "Returns the region of bucket.
  Uses the region= parameter of its repository URL first.
  With an endpoint override uses AWS_REGION, or us-east-1 if absent.
  Otherwise uses the region S3 reports, or us-east-1 if S3 reports none."
  [bucket client]
  (or (get @regions bucket)
      (let [region (or (when (endpoint-override)
                         (or (env/getenv "AWS_REGION") "us-east-1"))
                       (discover-region bucket client)
                       "us-east-1")]
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
  (let [{:keys [status body]} (http/request (merge {:method :get :throw false :timeout 2000
                                                     :client (http/client {:connect-timeout 1000})}
                                                    req))]
    (when (= 200 status) body)))

(defn- container-credentials []
  (when-let [uri (or (some->> (env/getenv "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI") (str "http://169.254.170.2"))
                     (env/getenv "AWS_CONTAINER_CREDENTIALS_FULL_URI"))]
    (some-> (get-ok (cond-> {:uri uri}
                      (env/getenv "AWS_CONTAINER_AUTHORIZATION_TOKEN")
                      (assoc :headers {"Authorization" (env/getenv "AWS_CONTAINER_AUTHORIZATION_TOKEN")})))
            json-credentials)))

(defn- instance-credentials []
  (when-not (= "true" (some-> (env/getenv "AWS_EC2_METADATA_DISABLED") str/lower-case))
    (try
      (let [host (or (some-> (env/getenv "AWS_EC2_METADATA_SERVICE_ENDPOINT") (str/replace #"/+$" ""))
                     "http://169.254.169.254")
            token (try (get-ok {:method :put :uri (str host "/latest/api/token")
                                :headers {"X-aws-ec2-metadata-token-ttl-seconds" "21600"}})
                       (catch Exception _ nil))
            headers (if token {"X-aws-ec2-metadata-token" token} {})
            base (str host "/latest/meta-data/iam/security-credentials/")]
        (when-let [role (some-> (get-ok {:uri base :headers headers}) str/split-lines first str/trim not-empty)]
          (some-> (get-ok {:uri (str base role) :headers headers}) json-credentials)))
      (catch Exception _ nil))))

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
                              container-credentials instance-credentials])]
        (reset! cached-credentials {:credentials creds :expiration (:expiration creds)})
        creds))))

(defn request
  "Returns the http request for a request on an s3:// url.
  Signs with the access key id and secret access key in :basic-auth, or the default credentials if absent.
  Leaves the request unsigned if there are no credentials."
  [{:keys [uri method basic-auth client] :as req}]
  (let [u (URI/create uri)
        bucket (.getHost u)
        region (bucket-region bucket client)
        {:keys [scheme host prefix]} (endpoint bucket region)
        path (str prefix (encode-path (.getPath u)))
        creds (if-let [[id secret] basic-auth]
                {:access-key-id id :secret-access-key secret}
                (default-credentials))]
    (cond-> (-> req
                (dissoc :basic-auth)
                (assoc :uri (str scheme "://" host path)))
      creds (update :headers merge (signed-headers {:method method :host host :path path
                                                    :region region :amz-date (amz-date)}
                                                   creds)))))
