(ns orchard.clojuredocs
  "Find docs from ClojureDocs and retrieve the result as a map."
  {:author "Masashi Iizuka"
   :added "0.5"}
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [orchard.misc :refer [with-lock]]
   [orchard.util.os :as os])
  (:import
   (java.io File)
   (java.net URI)
   (javax.net.ssl HttpsURLConnection)
   (java.util.concurrent.locks ReentrantLock)))

(def cache (atom {}))
(def ^:private lock
  "Lock to prevent concurrent loading and parsing of Clojuredocs data and writing
  it into cache. This lock provides only efficiency benefits and is not
  necessary for correct behavior as accessing atom that contains immutable data
  structures is safe without a lock."
  (ReentrantLock.))
(def default-edn-file-url
  "https://github.com/clojure-emacs/clojuredocs-export-edn/raw/master/exports/export.compact.edn")
(def ^File cache-file
  (io/file (os/cache-dir) "orchard" "clojuredocs" "export.edn"))

(def http-timeout
  "Timeout in milliseconds for connecting to a URL and reading the content."
  #_1000 ;; BB-PATCH the first lookup downloads the export, 1000 times out through GitHub's redirect
  10000)

(defn- slurp-with-timeout
  "Like `slurp`, but allows setting a timeout value in milliseconds if the
  resource is an URL. The maximum waiting time is technically 2x of `timeout`."
  [^String resource, timeout]
  (if (and (string? resource) (.startsWith resource "http"))
    (let [url (.toURL (URI. resource))
          conn ^HttpsURLConnection (.openConnection url)]
      (.setConnectTimeout conn timeout)
      (.setReadTimeout conn timeout)
      (if (= (.getResponseCode conn) 200)
        (slurp (.getInputStream conn))
        (throw (ex-info (format "%s: HTTP error code %s" url
                                (.getResponseCode conn)) {}))))

    ;; Non-remote URL - use regular slurp.
    (slurp resource)))

(defn- write-cache-file! [url]
  (.. cache-file
      getParentFile
      mkdirs)
  (spit cache-file (slurp-with-timeout url http-timeout)))

(defn- load-cache-file! [file]
  (reset! cache (-> file
                    slurp
                    edn/read-string))
  true)

(defn load-docs-if-not-loaded!
  "Load exported docs from bundled or cached file when no docs are loaded.
  The Cached file take priority."
  {:added "0.5"}
  []
  ;; Prevent multiple threads from trying to load the cache simultaneously.
  (with-lock lock
    (when (empty? @cache)
      (load-cache-file!
       (if (.exists cache-file)
         cache-file
         #_(io/resource "clojuredocs/export.edn") ;; BB-PATCH the image does not bundle the export, download it to the cache file
         (do (write-cache-file! default-edn-file-url) cache-file))))))

(defn update-cache!
  "Load exported docs file from ClojureDocs, and store it as a cache.
  `export-edn-url` should be the URL to EDN file with Clojuredocs
  data (`default-edn-file-url` if not provided)."
  {:added "0.5"}
  ([]
   (update-cache! default-edn-file-url))
  ([export-edn-url]
   (with-lock lock
     (write-cache-file! export-edn-url)
     (load-cache-file! cache-file))))

(defn clean-cache!
  "Clean the cached ClojureDocs export file and the in memory cache."
  {:added "0.5"}
  []
  (.delete cache-file)
  (reset! cache {}))

(defn get-doc
  "Get data for `var-name`.
  Bundled documentation will be used when there is no cached documentation."
  {:added "0.5"}
  [var-name]
  (load-docs-if-not-loaded!)
  (get @cache (keyword var-name)))

(defn find-doc
  "Find documentation matching `ns` and `sym` from the cached documentation.
  Bundled documentation will be used when there is no cached documentation.

  Return nil if there is no matching documentation."
  {:added "0.5"}
  [ns sym]
  (get-doc (keyword ns sym)))

(defn- var-name
  "Convert `v`'s name to a string we can use with `get-doc`."
  [v]
  (subs (str v) 2))

(defn- try-ns-resolve [ns sym]
  (try
    (ns-resolve ns sym)
    (catch Exception _
      nil)))

(defn resolve-and-find-doc
  "Resolve `sym` in the context of `ns` and look up the documentation
  for the resulting var."
  {:added "0.5"}
  [ns sym]
  (if (special-symbol? sym)
    (find-doc "clojure.core" (str sym))
    (some-> (try-ns-resolve ns sym) var-name get-doc)))

(defn- kw-to-sym [kw]
  (symbol (subs (str kw) 1)))

(defn see-also
  "Get the see-alsos for `var-name` if any."
  {:added "0.5"}
  [var-name]
  (when-let [see-alsos (:see-alsos (get-doc var-name))]
    (map kw-to-sym see-alsos)))
