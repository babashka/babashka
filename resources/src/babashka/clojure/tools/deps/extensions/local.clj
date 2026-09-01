;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.tools.deps.extensions.local
  (:require
    [clojure.java.io :as jio]
    [clojure.string :as str]
    [clojure.tools.deps.extensions :as ext]
    [clojure.tools.deps.extensions.pom :as pom]
    [clojure.tools.deps.util.dir :as dir]
    [clojure.tools.deps.util.maven :as maven]
    [clojure.tools.deps.util.session :as session])
  (:import
    [java.io File IOException]
    [java.net URL]
    [java.util.jar JarFile JarEntry]
    ;; maven-builder-support
    [org.apache.maven.model.building UrlModelSource]
    [org.apache.maven.model License]))

(defmethod ext/coord-type-keys :local
  [_type]
  #{:local/root})

(defmethod ext/dep-id :local
  [lib {:keys [local/root] :as _coord} _config]
  {:lib lib
   :root root})

(defn- ensure-file
  ^File [lib root]
  (let [f (jio/file root)]
    (if (.exists f)
      f
      (throw (ex-info (format "Local lib %s not found: %s" lib root) {:lib lib :root root})))))

(defmethod ext/canonicalize :local
  [lib {:keys [local/root] :as coord} _config]
  (let [canonical-root (.getCanonicalPath (dir/canonicalize (jio/file root)))]
    (ensure-file lib canonical-root) ;; throw if missing
    [lib (assoc coord :local/root canonical-root)]))

(defmethod ext/lib-location :local
  [_lib {:keys [local/root]} _config]
  {:base root
   :path ""
   :type :local})

(defmethod ext/find-versions :local
  [_lib _coord _type _config]
  nil)

(defmethod ext/manifest-type :local
  [lib {:keys [local/root deps/manifest] :as _coord} _config]
  (cond
    manifest {:deps/manifest manifest :deps/root root}
    (.isFile (ensure-file lib root)) {:deps/manifest :jar, :deps/root root}
    :else (ext/detect-manifest root)))

(defmethod ext/coord-summary :local [lib {:keys [local/root]}]
  (str lib " " root))

(defmethod ext/license-info :local
  [lib coord config]
  (let [coord (merge coord (ext/manifest-type lib coord config))]
    (ext/license-info-mf lib coord (:deps/manifest coord) config)))

(defn find-pom
  "Find path of pom file in jar file, or nil if it doesn't exist"
  [^JarFile jar-file]
  (try
    (loop [[^JarEntry entry & entries] (enumeration-seq (.entries jar-file))]
      (when entry
        (let [name (.getName entry)]
          (if (and (str/starts-with? name "META-INF/")
                (str/ends-with? name "pom.xml"))
            name
            (recur entries)))))
    (catch IOException _t nil)))

(defmethod ext/coord-deps :jar
  [lib {:keys [local/root] :as _coord} _manifest config]
  (let [jar (JarFile. (ensure-file lib root))]
    (if-let [path (find-pom jar)]
      (let [url (URL. (str "jar:file:" root "!/" path))
            src (UrlModelSource. url)
            settings (session/retrieve :mvn/settings #(maven/get-settings))
            model (pom/read-model src config settings)]
        (pom/model-deps model))
      [])))

(defmethod ext/coord-paths :jar
  [_lib coord _manifest _config]
  [(:local/root coord)])

;; 0 if x and y are the same jar or dir
(defmethod ext/compare-versions [:local :local]
  [lib {x-root :local/root :as x} {y-root :local/root :as y} _config]
  (if (= x-root y-root)
    0
    (throw (ex-info (str "No known ancestor relationship between local versions for " lib ": " x-root " and " y-root)
                    {:lib lib :x x :y y}))))

(defmethod ext/manifest-file :jar
  [_lib {:keys [deps/root] :as _coord} _mf _config]
  nil)

(defmethod ext/license-info-mf :jar
  [lib {:keys [local/root] :as _coord} _mf config]
  (let [jar (JarFile. (ensure-file lib root))]
    (when-let [path (find-pom jar)]
      (let [url (URL. (str "jar:file:" root "!/" path))
            src (UrlModelSource. url)
            settings (session/retrieve :mvn/settings #(maven/get-settings))
            model (pom/read-model src config settings)
            licenses (.getLicenses model)
            ^License license (when (and licenses (pos? (count licenses))) (first licenses))]
        (when license
          (let [name (.getName license)
                url (.getUrl license)]
            (when (or name url)
              (cond-> {}
                name (assoc :name name)
                url (assoc :url url)))))))))

(defmethod ext/coord-usage :jar
  [_lib _coord _manifest-type _config]
  ;; TBD
  nil)

(defmethod ext/prep-command :jar
  [_lib _coord _manifest-type _config]
  ;; TBD - could look in jar
  nil)