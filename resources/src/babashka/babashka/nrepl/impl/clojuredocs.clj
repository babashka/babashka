(ns babashka.nrepl.impl.clojuredocs
  "Implements the cider-nrepl ops `cider/clojuredocs-lookup` and
  `cider/clojuredocs-refresh-cache`."
  {:no-doc true}
  (:require
   [babashka.fs :as fs]
   [babashka.http-client :as http]
   [babashka.nrepl.impl.sci :as sci-helpers]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.transport :refer [safe-handle]]))

(def default-edn-file-url
  "https://github.com/clojure-emacs/clojuredocs-export-edn/raw/master/exports/export.compact.edn")

(defn- cache-dir []
  (let [os (str/lower-case (System/getProperty "os.name"))
        home (System/getProperty "user.home")]
    (cond
      (str/includes? os "mac") (fs/path home "Library" "Caches")
      (str/includes? os "win") (if-let [appdata (not-empty (System/getenv "LOCALAPPDATA"))]
                                 (fs/path appdata "Cache")
                                 (fs/path home "AppData" "Local" "Cache"))
      :else (fs/path (or (not-empty (System/getenv "XDG_CACHE_HOME"))
                         (fs/path home ".cache"))))))

(def ^:private cache-file
  (delay (fs/path (cache-dir) "orchard" "clojuredocs" "export.edn")))

(defonce ^:private cache (atom nil))

(defn- download! [url]
  (let [body (:body (http/get url {:timeout 10000}))]
    (fs/create-dirs (fs/parent @cache-file))
    (spit (fs/file @cache-file) body)))

(defn- load-cache-file! []
  (reset! cache (edn/read-string (slurp (fs/file @cache-file)))))

(defn- docs
  "Returns the ClojureDocs export from the cache file, or nil if the file is missing.
  Downloads the export to the cache file first if download? is true and the file is missing."
  [download?]
  (or @cache
      (locking cache
        (or @cache
            (do (when (and download? (not (fs/exists? @cache-file)))
                  (download! default-edn-file-url))
                (when (fs/exists? @cache-file)
                  (load-cache-file!)))))))

(defn- doc-key [{:keys [ns name]}]
  (when (and ns name)
    (keyword (str ns) (str name))))

(defn see-also
  "Returns the see-also names of lookup result m as strings, or nil if the export is not cached."
  [m]
  (when-let [k (doc-key m)]
    (try (some->> (get (docs false) k) :see-alsos (map #(subs (str %) 1)))
         (catch Exception _ nil))))

(defn- wire [x]
  (cond
    (map? x) (into {} (map (fn [[k v]] [(subs (str k) 1) (wire v)])) x)
    (sequential? x) (mapv wire x)
    (keyword? x) (subs (str x) 1)
    :else x))

(defn- lookup-reply [{:keys [ns sym]}]
  (if-let [doc (some->> (sci-helpers/lookup sym ns) doc-key (get (docs true)))]
    {:status :done :clojuredocs (wire doc)}
    {:status #{:done :no-doc}}))

(defn- refresh-cache-reply [{:keys [export-edn-url]}]
  (locking cache
    (download! (or export-edn-url default-edn-file-url))
    (load-cache-file!))
  {:status #{:done :ok}})

(defn wrap-clojuredocs
  "Middleware for the cider-nrepl ClojureDocs ops."
  [h]
  (fn [msg]
    (safe-handle msg
      "cider/clojuredocs-lookup" lookup-reply
      "clojuredocs-lookup" lookup-reply
      "cider/clojuredocs-refresh-cache" refresh-cache-reply
      "clojuredocs-refresh-cache" refresh-cache-reply
      :else h)))

(def ^:private lookup-descriptor
  {:doc "Returns the ClojureDocs entry of a symbol."
   :requires {"ns" "The namespace to resolve in." "sym" "The symbol to look up."}
   :optional {}
   :returns {"clojuredocs" "The ClojureDocs entry."}})

(def ^:private refresh-cache-descriptor
  {:doc "Downloads the ClojureDocs export to the cache file."
   :requires {}
   :optional {"export-edn-url" "The URL of the export."}
   :returns {}})

(set-descriptor! #'wrap-clojuredocs
                 {:requires #{}
                  :expects #{}
                  :handles {"cider/clojuredocs-lookup" lookup-descriptor
                            "clojuredocs-lookup" lookup-descriptor
                            "cider/clojuredocs-refresh-cache" refresh-cache-descriptor
                            "clojuredocs-refresh-cache" refresh-cache-descriptor}})
