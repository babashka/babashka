(ns babashka.impl.mvn.http
  "Repository downloads and checksum verification."
  {:no-doc true}
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.impl.mvn.ssl :as ssl]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

;; One write per line, so parallel downloads do not interleave words.
(defn- printerrln [& xs]
  (binding [*out* *err*]
    (print (str (str/join " " xs) "\n"))
    (flush)))

(def ^:private clients (atom {}))

(defn- client-for
  "An http client that goes through proxy and uses ssl-context, one per pair
  for the process. Either may be nil.
  Credentials go through an Authenticator, and the JDK's ban on Basic
  authentication for CONNECT tunnels is lifted for them, as Maven's own
  transport allows it."
  [{:keys [host port username password] :as proxy} ssl-context]
  (or (get @clients [proxy ssl-context])
      (let [credentials? (and username password)
            _ (when credentials?
                (System/setProperty "jdk.http.auth.tunneling.disabledSchemes" ""))
            client (http/client (cond-> {:follow-redirects :normal}
                                  proxy (assoc :proxy {:host host :port port})
                                  credentials? (assoc :authenticator {:user username :pass password})
                                  ssl-context (assoc :ssl-context ssl-context)))]
        (swap! clients assoc [proxy ssl-context] client)
        client)))

;; The bundled tools.deps; script/vendor_bundled_sources.clj keeps it current.
(def ^:private tools-deps-version "0.31.1638")

(defn- user-agent
  "Returns the aether.connector.userAgent system property, or
  babashka/<version> tools.deps/<version> by default."
  []
  (or (System/getProperty "aether.connector.userAgent")
      (str "babashka/" (or (System/getProperty "babashka.version") "unknown")
           " tools.deps/" tools-deps-version)))

(defn- request-opts
  "Returns request options for a repository's :auth, :proxy and :headers."
  [{:keys [auth proxy headers]}]
  (let [ssl-context (ssl/ssl-context)]
    (cond-> {:as :stream
             :throw false
             :follow-redirects :normal
             :timeout 120000
             :headers (merge (when-not (some #(.equalsIgnoreCase "User-Agent" ^String %) (keys headers))
                               {"User-Agent" (user-agent)})
                             headers)}
      auth (assoc :basic-auth auth)
      (or proxy ssl-context) (assoc :client (client-for proxy ssl-context)))))

(defn- root-message
  "Returns the message of the innermost cause, or nil."
  [^Throwable e]
  (let [root (loop [t e] (if-let [c (.getCause t)] (recur c) t))]
    (.getMessage root)))

(defn- request!
  "A request with a transport failure reported as Aether reports it: what
  could not be transferred, from which repository, and why. An unresolved
  host has no message of its own, so the host stands in."
  [method url {:keys [repo-id repo-url label] :as opts}]
  (try
    (http/request (assoc opts :method method :uri url))
    (catch Exception e
      (let [base (if (and label (str/ends-with? url label))
                   (subs url 0 (- (count url) (count label)))
                   url)
            why (or (root-message e) (.getHost (java.net.URI. url)))]
        (throw (ex-info (str "Could not transfer " (or label url)
                             " from " (or repo-id base) " (" (or repo-url base) "): " why)
                        {:url url :repo-id repo-id} e))))))

(defn- file-url? [url]
  (str/starts-with? url "file:"))

(defn- file-url->path
  "Converts a file: URL to a decoded file path.
  Falls back to the literal path if URI conversion fails."
  [url]
  (or (try (str (java.nio.file.Paths/get (java.net.URI. url)))
           (catch Exception _ nil))
      (-> url (str/replace-first #"^file:(//)?" "") (str/replace #"^/+" "/"))))

(defn fetch
  "Returns the contents of url as a string, or nil when the file is absent."
  [url opts]
  (if (file-url? url)
    (let [f (file-url->path url)]
      (when (fs/exists? f) (slurp f)))
    (let [{:keys [status body]} (request! :get url (assoc (request-opts opts) :as :string
                                                          :repo-id (:repo-id opts) :repo-url (:repo-url opts) :label (:label opts)))]
      (cond
        (= 200 status) body
        (#{404 410} status) nil
        :else (throw (ex-info (str "HTTP " status " for " url) {:url url :status status}))))))

(defn exists?
  "Whether url exists, checked with a HEAD request like Aether's existence
  check, or on disk for a file: URL."
  [url opts]
  (if (file-url? url)
    (fs/exists? (file-url->path url))
    (let [{:keys [status]} (request! :head url (assoc (request-opts opts) :as :string
                                                      :repo-id (:repo-id opts) :repo-url (:repo-url opts) :label (:label opts)))]
      (cond
        (= 200 status) true
        (#{404 410} status) false
        :else (throw (ex-info (str "HTTP " status " for " url) {:url url :status status}))))))

(defn- included-checksums
  "Returns the checksums in the response headers of a file as [extension algorithm checksum] vectors, sha1 first.
  The x-checksum-sha1 and x-checksum-md5 headers take precedence over x-goog-meta-checksum-sha1 and x-goog-meta-checksum-md5, which take precedence over SHA1{...} in the ETag."
  [headers]
  (let [named (fn [prefix]
                (not-empty (into []
                                 (keep (fn [[ext algorithm suffix]]
                                         (when-let [value (get headers (str prefix suffix))]
                                           [ext algorithm value])))
                                 [[".sha1" "SHA-1" "sha1"] [".md5" "MD5" "md5"]])))]
    (or (named "x-checksum-")
        (named "x-goog-meta-checksum-")
        (when-let [etag (get headers "etag")]
          (let [start (str/index-of etag "SHA1{")
                end (when start (str/index-of etag "}" (+ start 5)))]
            (when end
              [[".sha1" "SHA-1" (subs etag (+ start 5) end)]]))))))

(defn- fetch-to-file
  "Writes the contents of url to dest and prints the download on stderr.
  Returns a map with the :included checksums of the response, or nil if the file is absent."
  [url dest {:keys [repo-id repo-url label] :as opts}]
  (if (file-url? url)
    (let [f (file-url->path url)]
      (when (fs/exists? f)
        (printerrln "Downloading:" label "from" repo-id)
        (fs/copy f dest {:replace-existing true})
        {}))
    (let [{:keys [status body headers]} (request! :get url (assoc (request-opts opts) :repo-id repo-id :repo-url repo-url :label label))]
      ;; Close the response body for every status.
      (try
        (cond
          (= 200 status) (do (printerrln "Downloading:" label "from" repo-id)
                             (io/copy body (io/file dest))
                             {:included (included-checksums headers)})
          (#{404 410} status) nil
          :else (throw (ex-info (str "HTTP " status " for " url) {:url url :status status})))
        (finally
          (when (instance? java.io.Closeable body)
            (.close ^java.io.Closeable body)))))))

(defn- hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn- digest [algorithm file]
  (let [md (MessageDigest/getInstance algorithm)
        buf (byte-array 8192)]
    (with-open [in (io/input-stream file)]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update md buf 0 n)
            (recur)))))
    (hex (.digest md))))

(defn- parse-checksum
  "The checksum in a sidecar file, read the way ChecksumUtils reads it:
  the first non-blank line, the last word of a \"name = sum\" line and
  the first word of any other."
  [text]
  (let [line (or (some #(let [l (str/trim %)] (when (seq l) l)) (str/split-lines text)) "")]
    (if (re-matches #".+= [0-9A-Fa-f]+" line)
      (subs line (inc (str/last-index-of line " ")))
      (if-let [i (str/index-of line " ")]
        (subs line 0 i)
        line))))

(defn- remote-checksum
  "Returns [extension algorithm checksum] of the sha1 published next to url, or of the md5 if no sha1 is published.
  Returns nil if neither is published.
  Treats a failed request as not published."
  [url opts]
  (some (fn [[ext algorithm]]
          (when-let [text (try (fetch (str url ext) opts)
                               (catch Exception _ nil))]
            [ext algorithm (parse-checksum text)]))
        [[".sha1" "SHA-1"] [".md5" "MD5"]]))

(defn- verify
  "Checks a downloaded file against the first included checksum, or against the checksum published next to url if included is empty.
  Returns nil under the :ignore policy.
  Returns a map with :ext and :checksum of the published checksum, if any.
  The map has :message and :data if the file fails the check, and :retry true for a checksum mismatch."
  [url file included {:keys [checksum label] :or {checksum :warn} :as opts}]
  (when-not (= :ignore checksum)
    (let [[ext algorithm expected] (or (first included) (remote-checksum url opts))
          actual (when expected (digest algorithm file))]
      (cond
        (nil? expected)
        {:message (str "Checksum validation failed for " label ", no checksums available")
         :data {:url url}}

        (not (.equalsIgnoreCase ^String expected actual))
        {:ext ext :checksum expected :retry true
         :message (str "Checksum validation failed for " label ", expected " expected " but is " actual)
         :data {:url url :expected expected :actual actual}}

        :else
        {:ext ext :checksum expected}))))

(defn temp-file
  "Returns a path next to file named file.<random>.tmp."
  [file]
  ;; unique across processes started together
  (str file "." (java.util.UUID/randomUUID) ".tmp"))

(def ^:private windows? (fs/windows?))

(defn move-into-place!
  "Moves tmp over file atomically. On Windows writes it into file in place,
  as Aether does, since replacing a file another process holds open fails
  there. Deletes tmp."
  [tmp file]
  (try
    (if windows?
      (with-open [in (io/input-stream (fs/file tmp))
                  out (io/output-stream (fs/file file))]
        (io/copy in out))
      (fs/move tmp file {:atomic-move true :replace-existing true}))
    (finally
      (fs/delete-if-exists tmp))))

(defn- fetch-verified
  "Downloads url to tmp and verifies it. Downloads once more after a checksum mismatch.
  Returns the map from verify, {} under the :ignore policy, or nil if the file is absent.
  Throws under the :fail policy if the last download fails verification."
  [url tmp {:keys [checksum] :as opts}]
  (loop [trial 0]
    (when-let [{:keys [included]} (fetch-to-file url tmp opts)]
      (let [{:keys [message data retry] :as result} (verify url tmp included opts)]
        (cond
          (and retry (zero? trial)) (do (printerrln message)
                                        (recur 1))
          (and message (= :fail checksum)) (throw (ex-info message data))
          :else (do (when message (printerrln message))
                    (or result {})))))))

(defn download!
  "Downloads url to dest atomically and verifies the checksum using opts.
  Writes the published checksum of an accepted file next to dest, as dest.sha1 or dest.md5.
  Returns dest, or nil when the file is absent.
  opts: :auth [user pass], :proxy, :headers, :checksum :warn/:fail/:ignore, :repo-id,
  :repo-url and :label for messages."
  [url dest opts]
  (let [dest (str dest)
        tmp (temp-file dest)]
    (fs/create-dirs (fs/parent dest))
    (try
      (when-let [{:keys [ext checksum]} (fetch-verified url tmp opts)]
        (move-into-place! tmp dest)
        (when checksum
          (let [sidecar (str dest ext)
                tmp (temp-file sidecar)]
            (spit tmp checksum)
            (move-into-place! tmp sidecar)))
        dest)
      (finally
        (fs/delete-if-exists tmp)))))
