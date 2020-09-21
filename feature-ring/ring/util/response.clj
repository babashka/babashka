(ns ring.util.response
  "Functions for generating and augmenting response maps."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ring.util.io :refer [last-modified-date]]
            [ring.util.parsing :as parsing]
            [ring.util.time :refer [format-date]])
  (:import [java.io File]
           [java.util Date]
           [java.net URL URLDecoder URLEncoder]))

(def ^{:added "1.4"} redirect-status-codes
  "Map a keyword to a redirect status code."
  {:moved-permanently 301
   :found 302
   :see-other 303
   :temporary-redirect 307
   :permanent-redirect 308})

(defn redirect
  "Returns a Ring response for an HTTP 302 redirect. Status may be 
  a key in redirect-status-codes or a numeric code. Defaults to 302"
  ([url] (redirect url :found))
  ([url status]
   {:status  (redirect-status-codes status status)
    :headers {"Location" url}
    :body    ""}))

(defn redirect-after-post
  "Returns a Ring response for an HTTP 303 redirect. Deprecated in favor
  of using redirect with a :see-other status."
  {:deprecated "1.4"}
  [url]
  {:status  303
   :headers {"Location" url}
   :body    ""})

(defn created
  "Returns a Ring response for a HTTP 201 created response."
  {:added "1.2"}
  ([url] (created url nil))
  ([url body]
     {:status  201
      :headers {"Location" url}
      :body    body}))

(defn bad-request
  "Returns a 400 'bad request' response."
  {:added "1.7"}
  [body]
  {:status  400
   :headers {}
   :body    body})

(defn not-found
  "Returns a 404 'not found' response."
  {:added "1.1"}
  [body]
  {:status  404
   :headers {}
   :body    body})

(defn response
  "Returns a skeletal Ring response with the given body, status of 200, and no
  headers."
  [body]
  {:status  200
   :headers {}
   :body    body})

(defn status
  "Returns an updated Ring response with the given status."
  ([status]
   {:status  status
    :headers {}
    :body    nil})
  ([resp status]
   (assoc resp :status status)))

(defn header
  "Returns an updated Ring response with the specified header added."
  [resp name value]
  (assoc-in resp [:headers name] (str value)))

(defn- canonical-path ^String [^File file]
  (str (.getCanonicalPath file)
       (if (.isDirectory file) File/separatorChar)))

(defn- safe-path? [^String root ^String path]
  (.startsWith (canonical-path (File. root path))
               (canonical-path (File. root))))

(defn- directory-transversal?
  "Check if a path contains '..'."
  [^String path]
  (-> (str/split path #"/|\\")
      (set)
      (contains? "..")))

(defn- find-file-named [^File dir ^String filename]
  (let [path (File. dir filename)]
    (if (.isFile path)
      path)))

(defn- find-file-starting-with [^File dir ^String prefix]
  (first
   (filter
    #(.startsWith (.toLowerCase (.getName ^File %)) prefix)
    (.listFiles dir))))

(defn- find-index-file
  "Search the directory for an index file."
  [^File dir]
  (or (find-file-named dir "index.html")
      (find-file-named dir "index.htm")
      (find-file-starting-with dir "index.")))

(defn- safely-find-file [^String path opts]
  (if-let [^String root (:root opts)]
    (if (or (safe-path? root path)
            (and (:allow-symlinks? opts) (not (directory-transversal? path))))
      (File. root path))
    (File. path)))

(defn- find-file [^String path opts]
  (if-let [^File file (safely-find-file path opts)]
    (cond
      (.isDirectory file)
        (and (:index-files? opts true) (find-index-file file))
      (.exists file)
        file)))

(defn- file-data [^File file]
  {:content        file
   :content-length (.length file)
   :last-modified  (last-modified-date file)})

(defn- content-length [resp len]
  (if len
    (header resp "Content-Length" len)
    resp))

(defn- last-modified [resp last-mod]
  (if last-mod
    (header resp "Last-Modified" (format-date last-mod))
    resp))

(defn file-response
  "Returns a Ring response to serve a static file, or nil if an appropriate
  file does not exist.
  Options:
    :root            - take the filepath relative to this root path
    :index-files?    - look for index.* files in directories (defaults to true)
    :allow-symlinks? - allow symlinks that lead to paths outside the root path
                       (defaults to false)"
  ([filepath]
   (file-response filepath {}))
  ([filepath options]
   (if-let [file (find-file filepath options)]
     (let [data (file-data file)]
       (-> (response (:content data))
           (content-length (:content-length data))
           (last-modified (:last-modified data)))))))

;; In Clojure 1.5.1, the as-file function does not correctly decode
;; UTF-8 byte sequences.
;;
;; See: http://dev.clojure.org/jira/browse/CLJ-1177
;;
;; As a work-around, we'll backport the fix from CLJ-1177 into
;; url-as-file.

(defn- ^File url-as-file [^java.net.URL u]
  (-> (.getFile u)
      (str/replace \/ File/separatorChar)
      (str/replace "+" (URLEncoder/encode "+" "UTF-8"))
      (URLDecoder/decode "UTF-8")
      io/as-file))

(defn content-type
  "Returns an updated Ring response with the a Content-Type header corresponding
  to the given content-type."
  [resp content-type]
  (header resp "Content-Type" content-type))

(defn find-header
  "Looks up a header in a Ring response (or request) case insensitively,
  returning the header map entry, or nil if not present."
  {:added "1.4"}
  [resp ^String header-name]
  (->> (:headers resp)
       (filter #(.equalsIgnoreCase header-name (key %)))
       (first)))

(defn get-header
  "Looks up a header in a Ring response (or request) case insensitively,
  returning the value of the header, or nil if not present."
  {:added "1.2"}
  [resp header-name]
  (some-> resp (find-header header-name) val))

(defn update-header
  "Looks up a header in a Ring response (or request) case insensitively,
  then updates the header with the supplied function and arguments in the
  manner of update-in."
  {:added "1.4"}
  [resp header-name f & args]
  (let [header-key (or (some-> resp (find-header header-name) key) header-name)]
    (update-in resp [:headers header-key] #(apply f % args))))

(defn charset
  "Returns an updated Ring response with the supplied charset added to the
  Content-Type header."
  {:added "1.1"}
  [resp charset]
  (update-header resp "Content-Type"
    (fn [content-type]
      (-> (or content-type "text/plain")
          (str/replace #";\s*charset=[^;]*" "")
          (str "; charset=" charset)))))

(defn get-charset
  "Gets the character encoding of a Ring response."
  {:added "1.6"}
  [resp]
  (some-> (get-header resp "Content-Type")
          parsing/find-content-type-charset))

(defn set-cookie
  "Sets a cookie on the response. Requires the handler to be wrapped in the
  wrap-cookies middleware."
  {:added "1.1"}
  [resp name value & [opts]]
  (assoc-in resp [:cookies name] (merge {:value value} opts)))

(defn response?
  "True if the supplied value is a valid response map."
  {:added "1.1"}
  [resp]
  (and (map? resp)
       (integer? (:status resp))
       (map? (:headers resp))))

(defmulti resource-data
  "Returns data about the resource specified by url, or nil if an
  appropriate resource does not exist.

  The return value is a map with optional values for:
  :content        - the content of the URL, suitable for use as the :body
                    of a ring response
  :content-length - the length of the :content, nil if not available
  :last-modified  - the Date the :content was last modified, nil if not
                    available

  This dispatches on the protocol of the URL as a keyword, and
  implementations are provided for :file and :jar. If you are on a
  platform where (Class/getResource) returns URLs with a different
  protocol, you will need to provide an implementation for that
  protocol.

  This function is used internally by url-response."
  {:arglists '([url]), :added "1.4"}
  (fn [^java.net.URL url]
    (keyword (.getProtocol url))))

(defmethod resource-data :file
  [url]
  (if-let [file (url-as-file url)]
    (if-not (.isDirectory file)
      (file-data file))))

(defn- add-ending-slash [^String path]
  (if (.endsWith path "/")
    path
    (str path "/")))

(defn- jar-directory? [^java.net.JarURLConnection conn]
  (let [jar-file   (.getJarFile conn)
        entry-name (.getEntryName conn)
        dir-entry  (.getEntry jar-file (add-ending-slash entry-name))]
    (and dir-entry (.isDirectory dir-entry))))

(defn- connection-content-length [^java.net.URLConnection conn]
  (let [len (.getContentLength conn)]
    (if (<= 0 len) len)))

(defn- connection-last-modified [^java.net.URLConnection conn]
  (let [last-mod (.getLastModified conn)]
    (if-not (zero? last-mod)
      (Date. last-mod))))

(defmethod resource-data :jar
  [^java.net.URL url]
  (let [conn (.openConnection url)]
    (if-not (jar-directory? conn)
      {:content        (.getInputStream conn)
       :content-length (connection-content-length conn)
       :last-modified  (connection-last-modified conn)})))

(defn url-response
  "Return a response for the supplied URL."
  {:added "1.2"}
  [^URL url]
  (if-let [data (resource-data url)]
    (-> (response (:content data))
        (content-length (:content-length data))
        (last-modified (:last-modified data)))))

(defn- get-resources [path ^ClassLoader loader]
  (-> (or loader (.getContextClassLoader (Thread/currentThread)))
      (.getResources path)
      (enumeration-seq)))

(defn- safe-file-resource? [{:keys [body]} {:keys [root loader allow-symlinks?]}]
  (or allow-symlinks?
      (nil? root)
      (let [root (.replaceAll (str root) "^/" "")]
        (or (str/blank? root)
            (let [path (canonical-path body)]
              (some #(and (= "file" (.getProtocol ^URL %))
                          (.startsWith path (canonical-path (url-as-file %))))
                    (get-resources root loader)))))))

(defn resource-response
  "Returns a Ring response to serve a packaged resource, or nil if the
  resource does not exist.
  Options:
    :root            - take the resource relative to this root
    :loader          - resolve the resource in this class loader
    :allow-symlinks? - allow symlinks that lead to paths outside the root
                       classpath directories (defaults to false)"
  ([path]
   (resource-response path {}))
  ([path options]
   (let [path      (-> (str "/" path)  (.replace "//" "/"))
         root+path (-> (str (:root options) path) (.replaceAll "^/" ""))
         load      #(if-let [loader (:loader options)]
                      (io/resource % loader)
                      (io/resource %))]
     (if-not (directory-transversal? root+path)
       (if-let [resource (load root+path)]
         (let [response (url-response resource)]
           (if (or (not (instance? File (:body response)))
                   (safe-file-resource? response options))
             response)))))))
