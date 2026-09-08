(ns babashka.mvn.http
  "Fetching repository files, with checksums."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

;; One write per line, so parallel downloads do not interleave words.
(defn- printerrln [& xs]
  (binding [*out* *err*]
    (print (str (str/join " " xs) "\n"))
    (flush)))

(def ^:private proxy-clients (atom {}))

(defn- client-for
  "An http client that goes through proxy, one per proxy for the process.
  Credentials go through an Authenticator, and the JDK's ban on Basic
  authentication for CONNECT tunnels is lifted for them, as Maven's own
  transport allows it."
  [{:keys [host port username password] :as proxy}]
  (or (get @proxy-clients proxy)
      (let [credentials? (and username password)
            _ (when credentials?
                (System/setProperty "jdk.http.auth.tunneling.disabledSchemes" ""))
            client (http/client (cond-> {:proxy {:host host :port port}
                                         :follow-redirects :normal}
                                  credentials? (assoc :authenticator {:user username :pass password})))]
        (swap! proxy-clients assoc proxy client)
        client)))

(defn- request-opts [{:keys [auth proxy]}]
  (cond-> {:as :stream
           :throw false
           :follow-redirects :normal
           :timeout 120000
           :headers {"User-Agent" "babashka"}}
    auth (assoc :basic-auth auth)
    proxy (assoc :client (client-for proxy))))

(defn- root-message
  "The message of the innermost cause, or its class when it has none, the
  way an unresolved host shows up."
  [^Throwable e]
  (let [root (loop [t e] (if-let [c (.getCause t)] (recur c) t))]
    (.getMessage root)))

(defn- get!
  "GET with a transport failure reported as Aether reports it: what could
  not be transferred, from which repository, and why. An unresolved host
  has no message of its own, so the host stands in."
  [url {:keys [repo-id label] :as opts}]
  (try
    (http/get url opts)
    (catch Exception e
      (let [base (if (and label (str/ends-with? url label))
                   (subs url 0 (- (count url) (count label)))
                   url)
            why (or (root-message e) (.getHost (java.net.URI. url)))]
        (throw (ex-info (str "Could not transfer " (or label url)
                             " from " (or repo-id base) " (" base "): " why)
                        {:url url :repo-id repo-id} e))))))

(defn- file-url? [url]
  (str/starts-with? url "file:"))

(defn- file-url->path [url]
  (-> url (str/replace-first #"^file:(//)?" "") (str/replace #"^/+" "/")))

(defn fetch
  "GET url as a string. nil when the server has no such file."
  [url opts]
  (if (file-url? url)
    (let [f (file-url->path url)]
      (when (fs/exists? f) (slurp f)))
    (let [{:keys [status body]} (get! url (assoc (request-opts opts) :as :string
                                                 :repo-id (:repo-id opts) :label (:label opts)))]
      (cond
        (= 200 status) body
        (#{404 410} status) nil
        :else (throw (ex-info (str "HTTP " status " for " url) {:url url :status status}))))))

(defn- fetch-to-file
  "GET url into dest. true when written, false when absent. Says so on
  stderr once the repository has answered, like Aether's transfer
  listener."
  [url dest {:keys [repo-id label] :as opts}]
  (if (file-url? url)
    (let [f (file-url->path url)]
      (if (fs/exists? f)
        (do (printerrln "Downloading:" label "from" repo-id)
            (fs/copy f dest {:replace-existing true})
            true)
        false))
    (let [{:keys [status body]} (get! url (assoc (request-opts opts) :repo-id repo-id :label label))]
      (cond
        (= 200 status) (do (printerrln "Downloading:" label "from" repo-id)
                           (with-open [in body]
                             (io/copy in (io/file dest))))
        (#{404 410} status) false
        :else (throw (ex-info (str "HTTP " status " for " url) {:url url :status status})))
      (= 200 status))))

(defn- hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn- sha1 [file]
  (let [md (MessageDigest/getInstance "SHA-1")
        buf (byte-array 8192)]
    (with-open [in (io/input-stream file)]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update md buf 0 n)
            (recur)))))
    (hex (.digest md))))

(defn- remote-checksum
  "The sha1 published next to url, or nil."
  [url opts]
  (some-> (fetch (str url ".sha1") opts)
          str/trim
          (str/split #"\s+")
          first
          str/lower-case))

(defn- verify!
  "Applies the checksum policy to a downloaded file."
  [url file {:keys [checksum label] :or {checksum :warn} :as opts}]
  (when-not (= :ignore checksum)
    (let [expected (remote-checksum url opts)
          actual (when expected (sha1 file))]
      (cond
        (nil? expected)
        (let [message (str "Checksum validation failed for " label ", no checksums available")]
          (if (= :fail checksum)
            (throw (ex-info message {:url url}))
            (printerrln message)))

        (not= expected actual)
        (let [message (str "Checksum validation failed for " label
                           ", expected " expected " but is " actual)]
          (if (= :fail checksum)
            (throw (ex-info message {:url url :expected expected :actual actual}))
            (printerrln message)))))))

(defn download!
  "Downloads url to dest, atomically, and verifies the checksum per the
  policy in opts. Returns dest, or nil when the repository has no such file.
  opts: :auth [user pass], :proxy, :checksum :warn/:fail/:ignore, :repo-id
  and :label for messages."
  [url dest opts]
  (let [dest (str dest)
        part (str dest ".part")]
    (fs/create-dirs (fs/parent dest))
    (try
      (when (fetch-to-file url part opts)
        (verify! url part opts)
        (fs/move part dest {:replace-existing true})
        dest)
      (finally
        (when (fs/exists? part) (fs/delete part))))))
