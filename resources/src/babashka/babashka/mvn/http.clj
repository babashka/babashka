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

(defn- request-opts [{:keys [auth proxy]}]
  (cond-> {:as :stream
           :throw false
           :follow-redirects :normal
           :timeout 120000
           :headers {"User-Agent" "babashka"}}
    auth (assoc :basic-auth auth)
    proxy (assoc :proxy proxy)))

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
    (let [{:keys [status body]} (http/get url (assoc (request-opts opts) :as :string))]
      (cond
        (= 200 status) body
        (#{404 410} status) nil
        :else (throw (ex-info (str "HTTP " status " for " url) {:url url :status status}))))))

(defn- fetch-to-file
  "GET url into dest. true when written, false when absent."
  [url dest opts]
  (if (file-url? url)
    (let [f (file-url->path url)]
      (if (fs/exists? f)
        (do (fs/copy f dest {:replace-existing true}) true)
        false))
    (let [{:keys [status body]} (http/get url (request-opts opts))]
      (cond
        (= 200 status) (with-open [in body]
                         (io/copy in (io/file dest)))
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
        (when (= :fail checksum)
          (throw (ex-info (str "No checksum available for " label) {:url url})))

        (not= expected actual)
        (if (= :fail checksum)
          (throw (ex-info (str "Checksum validation failed for " label
                               ", expected " expected " but is " actual)
                          {:url url :expected expected :actual actual}))
          (printerrln "Checksum validation failed for" label
                      "expected" expected "but is" actual))))))

(defn download!
  "Downloads url to dest, atomically, and verifies the checksum per the
  policy in opts. Returns dest, or nil when the repository has no such file.
  opts: :auth [user pass], :proxy, :checksum :warn/:fail/:ignore, :repo-id
  and :label for messages."
  [url dest {:keys [repo-id label] :as opts}]
  (let [dest (str dest)
        part (str dest ".part")]
    (fs/create-dirs (fs/parent dest))
    (printerrln "Downloading:" label "from" repo-id)
    (try
      (when (fetch-to-file url part opts)
        (verify! url part opts)
        (fs/move part dest {:replace-existing true})
        dest)
      (finally
        (when (fs/exists? part) (fs/delete part))))))
