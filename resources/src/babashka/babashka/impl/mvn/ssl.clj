(ns babashka.impl.mvn.ssl
  "The SSL context for repository downloads, from javax.net.ssl properties
  given to bb, in CLJ_JVM_OPTS or in JAVA_TOOL_OPTIONS."
  {:no-doc true}
  (:require [babashka.fs :as fs]
            [babashka.impl.mvn.env :as env]
            [babashka.impl.mvn.xml :as x]
            [babashka.process :as p]
            [clojure.string :as str])
  (:import [java.security KeyStore MessageDigest]
           [java.security.cert CertificateFactory X509Certificate]
           [javax.net.ssl KeyManagerFactory SSLContext TrustManagerFactory]))

(def ^:private property-names
  ["javax.net.ssl.trustStore" "javax.net.ssl.trustStoreType" "javax.net.ssl.trustStorePassword"
   "javax.net.ssl.keyStore" "javax.net.ssl.keyStoreType" "javax.net.ssl.keyStorePassword"])

(defn- jvm-opts-properties
  "Returns the -D properties in the JVM options s as a map."
  [s]
  (into {}
        (keep #(when-let [[_ k v] (re-matches #"-D([^=]+)=(.*)" %)] [k v]))
        (str/split (str/trim (or s "")) #"\s+")))

(defn properties
  "Returns the javax.net.ssl properties as a map.
  A system property of bb wins over CLJ_JVM_OPTS.
  CLJ_JVM_OPTS wins over JAVA_TOOL_OPTIONS."
  []
  (let [opts (merge (jvm-opts-properties (env/getenv "JAVA_TOOL_OPTIONS"))
                    (jvm-opts-properties (env/getenv "CLJ_JVM_OPTS")))]
    (into {}
          (keep #(when-let [v (or (System/getProperty %) (get opts %))] [% v]))
          property-names)))

(defn- plist-value [el]
  (case (x/tag-name el)
    "dict" (into {} (map (fn [[k v]] [(x/text k) (plist-value v)]))
                 (partition 2 (x/elements el)))
    "array" (mapv plist-value (x/elements el))
    "integer" (parse-long (x/text el))
    (x/text el)))

(def ^:private ssl-policies #{nil "basicX509" "sslServer"})

(defn trusted?
  "Returns true if the macOS trust settings for a certificate make it a trust root for TLS.
  An empty list of settings means always trust."
  [settings]
  (let [results (->> settings
                     (filter #(ssl-policies (get % "kSecTrustSettingsPolicyName")))
                     (map #(get % "kSecTrustSettingsResult" 1)))]
    (or (empty? settings)
        (and (some #{1 2} results) (not (some #{3} results))))))

(defn trusted-hashes
  "Returns the SHA-1 hashes of the trusted certificates in the trust settings plist s."
  [s]
  (let [trust-list (get (plist-value (first (x/elements (x/parse s)))) "trustList")]
    (set (keep (fn [[sha1 {:strs [trustSettings]}]]
                 (when (trusted? trustSettings) sha1))
               trust-list))))

(defn- sha1 [^X509Certificate cert]
  (str/join (map #(format "%02X" %) (.digest (MessageDigest/getInstance "SHA-1") (.getEncoded cert)))))

(defn- keychain-trust-store
  "Returns a KeyStore with the certificates trusted in the user and admin domains of the macOS trust settings."
  []
  (let [hashes (into #{}
                     (mapcat (fn [domain-args]
                               (fs/with-temp-dir [dir {}]
                                 (let [f (str (fs/path dir "trust.plist"))
                                       {:keys [exit]} (apply p/shell {:out :string :err :string :continue true}
                                                             "security" "trust-settings-export" (conj domain-args f))]
                                   (when (zero? exit)
                                     (trusted-hashes (slurp f)))))))
                     [[] ["-d"]])
        pem (:out (p/shell {:out :bytes :err :string}
                           "security" "find-certificate" "-a" "-p"
                           (str (fs/path (fs/home) "Library/Keychains/login.keychain-db"))
                           "/Library/Keychains/System.keychain"
                           "/System/Library/Keychains/SystemRootCertificates.keychain"))
        certs (.generateCertificates (CertificateFactory/getInstance "X.509")
                                     (java.io.ByteArrayInputStream. pem))
        ks (doto (KeyStore/getInstance "PKCS12") (.load nil nil))]
    (doseq [cert certs
            :let [h (sha1 cert)]
            :when (contains? hashes h)]
      (.setCertificateEntry ks h cert))
    ks))

(defn- load-store
  "Returns the KeyStore of type in file, or nil if file is absent.
  On macOS a KeychainStore without the Apple provider is read with the security command."
  [file type password]
  (let [password (some-> password char-array)]
    (cond
      (and (= "KeychainStore" type) (str/starts-with? (System/getProperty "os.name") "Mac") (nil? (java.security.Security/getProvider "Apple")))
      (keychain-trust-store)

      (= "KeychainStore" type)
      (doto (KeyStore/getInstance type) (.load nil password))

      (and file (not= "NONE" file))
      (let [ks (KeyStore/getInstance (or type (KeyStore/getDefaultType)))]
        (with-open [in (java.io.FileInputStream. ^String file)]
          (.load ks in password))
        ks))))

(def ^:private contexts (atom {}))

(defn ssl-context
  "Returns an SSLContext for the javax.net.ssl properties, or nil if none is set."
  []
  (let [props (properties)]
    (when (seq props)
      (or (get @contexts props)
          (let [prop #(get props (str "javax.net.ssl." %))
                trust-store (load-store (prop "trustStore") (prop "trustStoreType") (prop "trustStorePassword"))
                key-store (load-store (prop "keyStore") (prop "keyStoreType") (prop "keyStorePassword"))
                tmf (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
                      (.init ^KeyStore trust-store))
                kmf (when key-store
                      (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
                        (.init key-store (some-> (prop "keyStorePassword") char-array))))
                ctx (doto (SSLContext/getInstance "TLS")
                      (.init (some-> kmf .getKeyManagers) (.getTrustManagers tmf) nil))]
            (swap! contexts assoc props ctx)
            ctx)))))
