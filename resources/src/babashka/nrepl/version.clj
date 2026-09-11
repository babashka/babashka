(ns nrepl.version
  "nREPL version information, read from the Maven-generated POM properties
  at build time."
  {:author "Colin Jones"
   :added  "0.5"}
  (:import java.util.Properties))

(defn- map-from-property-filepath [file]
  (try
    (let [file-reader (.. (Thread/currentThread)
                          (getContextClassLoader)
                          (getResourceAsStream file))
          props (Properties.)]
      (.load props file-reader)
      (into {} props))
    (catch Exception _e nil)))

(defn- get-properties-filename [group artifact]
  (str "META-INF/maven/" group "/" artifact "/pom.properties"))

(defn- get-version
  "Attempts to get the project version from system properties (set when running
  Leiningen), or a properties file based on the group and artifact ids (in jars
  built by Leiningen), or a default version passed in.  Falls back to an empty
  string when no default is present."
  ([group artifact]
   (get-version group artifact ""))
  ([group artifact default-version]
   (or (System/getProperty (str artifact ".version"))
       (-> (get-properties-filename group artifact)
           map-from-property-filepath
           (get "version"))
       default-version)))

(def ^{:private true} version-string
  "Current version of nREPL as a string.
  See also `version`."
  #_(get-version "nrepl" "nrepl") ;; BB-PATCH the pom.properties resource is not in the image
"1.7.0")

(def version
  "Current version of nREPL.
  Map of :major, :minor, :incremental, :qualifier, and :version-string."
  (assoc (->> version-string
              (re-find #"(\d+)\.(\d+)\.(\d+)-?(.*)")
              rest
              (map #(try (Integer/parseInt %) (catch Exception _e nil)))
              (zipmap [:major :minor :incremental :qualifier]))
         :version-string version-string))
