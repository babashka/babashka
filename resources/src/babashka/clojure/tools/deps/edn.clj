;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.deps.edn
  "Functions for reading, validating, and manipulating deps.edn
  files and data structures."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as jio]
    [clojure.string :as str]
    [clojure.tools.deps.specs :as specs]
    [clojure.tools.deps.util.dir :as dir]
    [clojure.walk :as walk])
  (:import
    [java.io BufferedReader File InputStreamReader PushbackReader Reader]
    [clojure.lang EdnReader$ReaderException]
    ))

(set! *warn-on-reflection* true)

;;;; Read

(defonce ^:private nl (System/getProperty "line.separator"))

(defn- printerrln
  "println to *err*"
  [& msgs]
  (binding [*out* *err*
            *print-readably* nil]
    (pr (str (str/join " " msgs) nl))
    (flush)))

(defn- io-err
  "Helper function to construct an ex-info for an exception reading
  file at path with the message format fmt (which should have one
  variable for the path)."
  ^Throwable [fmt & {:keys [path]}]
  (let [abs-path (.getAbsolutePath (jio/file path))]
    (ex-info (format fmt abs-path) {:path abs-path})))

(defn read-edn
  "Read edn from Reader r, which should contain exactly one edn value.
  If source exists but is blank, nil is returned.
  Throws if source is unreadable or contains multiple values.

  Opts:
    :path String path to file being read, for error reporting"
  [^Reader r & opts]
  (with-open [rdr (PushbackReader. r)]
    (let [EOF (Object.)]
      (try
        (let [val (edn/read {:default tagged-literal :eof EOF} rdr)]
          (if (identical? EOF val)
            nil ;; empty file
            (if (not (identical? EOF (edn/read {:eof EOF} rdr)))
              (throw (ex-info "Expected edn to contain a single value." {}))
              val)))
        (catch EdnReader$ReaderException e
          (throw (io-err (str (.getMessage e) " (%s)") opts)))
        (catch RuntimeException t
          (if (str/starts-with? (.getMessage t) "EOF while reading")
            (throw (io-err "Error reading edn, delimiter unmatched (%s)" opts))
            (throw (io-err (str "Error reading edn. " (.getMessage t) " (%s)") opts))))))))

(defn validate
  "Validate a deps-edn map according to the specs, throw if invalid.
  Returns the deps-edn map.

  Opts:
    :path String path to file being read"
  [deps-edn & opts]
  (if (specs/valid-deps? deps-edn)
    deps-edn
    (throw (io-err (str "Error reading deps %s. " (specs/explain-deps deps-edn)) opts))))

;;;; Canonicalize

(defn- canonicalize-sym
  [s & opts]
  (if (simple-symbol? s)
    (let [cs (as-> (name s) n (symbol n n))]
      (printerrln "DEPRECATED: Libs must be qualified, change" s "=>" cs
        (if-let [path (:path opts)] (str "(" path ")") ""))
      cs)
    s))

(defn- canonicalize-exclusions
  [{:keys [exclusions] :as coord} & opts]
  (if (seq (filter simple-symbol? exclusions))
    (assoc coord :exclusions (mapv #(canonicalize-sym % opts) exclusions))
    coord))

(defn- canonicalize-dep-map
  [deps-map & opts]
  (when deps-map
    (reduce-kv (fn [acc lib coord]
                 (let [new-lib (if (simple-symbol? lib) (canonicalize-sym lib opts) lib)
                       new-coord (canonicalize-exclusions coord opts)]
                   (assoc acc new-lib new-coord)))
      {} deps-map)))

(defn canonicalize
  "Canonicalize a deps.edn map (convert simple lib symbols to qualified lib symbols).
  Returns the deps-edn map.

  Opts:
    :path String path to file being read"
  [deps-edn & opts]
  (walk/postwalk
    (fn [x]
      (if (map? x)
        (reduce (fn [xr k]
                  (if-let [xm (get xr k)]
                    (assoc xr k (canonicalize-dep-map xm opts))
                    xr))
          x #{:deps :default-deps :override-deps :extra-deps :classpath-overrides})
        x))
    deps-edn))

;;;; Read deps with validation and canonicalization

(defn read-deps
  "Corece f to a file with jio/file, then read, validate, and canonicalize
  the deps.edn. This is the primary entry point for reading.
  Use read-edn, validate, or canonicalize for individual steps.

  Opts: none for now"
  [f & opts]
  (let [file (jio/file f)
        opts {:path (.getPath file)}]
    (when (.exists file)
      (-> file jio/reader (read-edn opts) (validate opts) (canonicalize opts)))))

;;;; Dep chain lookups

(defn root-deps
  "Read the root deps.edn resource from the classpath at the path
  clojure/tools/deps/deps.edn"
  []
  (let [url (jio/resource "clojure/tools/deps/deps.edn")]
    (read-edn (BufferedReader. (InputStreamReader. (.openStream url))))))

(defn user-config-dir
  "Use the same logic as clj to calculate the location of the user config dir.
  Note that it's possible no dir may exist at this location.
  Return path as string."
  ^String []
  (let [config-env (System/getenv "CLJ_CONFIG")
        xdg-env (System/getenv "XDG_CONFIG_HOME")
        home (System/getProperty "user.home")]
    (cond config-env config-env
          xdg-env (str xdg-env File/separator "clojure")
          :else (str home File/separator ".clojure"))))

(defn user-deps-path
  "Use the same logic as clj to calculate the location of the user deps.edn.
  Note that it's possible no file may exist at this location.
  Returns path as string."
  ^String []
  (str (user-config-dir) File/separator "deps.edn"))

(defn user-deps
  "Calculate the user deps.edn per user-deps-path, read and
  return the deps.edn data"
  []
  (-> (user-deps-path) jio/file dir/canonicalize read-deps))

(defn project-dir
  "Calculate the project directory, usually . by default, but
  may be pushed to a new directory using the
  clojure.tools.deps.util.dir API."
  ^String []
  (.getPath dir/*the-dir*))

(defn project-deps-path
  "Calculate the project deps.edn location. This
  is the deps.edn in the current directory, as defined by
  clojure.tools.deps.util.dir/*the-dir* - use with-dir
  to push a new local directory context around a call to
  project-deps-path."
  ^String []
  (str (project-dir) File/separator "deps.edn"))

(defn project-deps
  "Calculate the project deps.edn location, read and return the deps.edn data.

  You may use clojure.tools.deps.util.dir/with-dir to read from a custom project
  dir, either absolute or relative to the current dir context."
  []
  (-> (project-deps-path) jio/file dir/canonicalize read-deps))

(defn- choose-deps
  [requested standard-fn]
  (cond
    (= :standard requested) (standard-fn)
    (string? requested) (-> requested jio/file dir/canonicalize read-deps)
    (or (nil? requested) (map? requested)) requested
    :else (throw (ex-info (format "Unexpected dep source: %s" (pr-str requested))
                   {:requested requested}))))

(defn create-edn-maps
  "Takes optional map of location sources, keys = :root :user :project :extra
  where each key may be:
    :standard (default) - to get the default source
    string - for file path to source, relative to current dir context
    nil - to omit
    map - a literal map to use

  Returns a set of deps edn maps with the same keys :root :user :project :extra.
  Keys may be missing if source was nil or file was missing."
  ([]
   (create-edn-maps nil))
  ([{:keys [root user project extra] :as params
     :or {root :standard, user :standard, project :standard}}]
   (let [root-edn (choose-deps root #(root-deps))
         user-edn (choose-deps user #(user-deps))
         project-edn (choose-deps project #(project-deps))
         extra-edn (choose-deps extra (constantly nil))]
     (cond-> {}
       root-edn (assoc :root root-edn)
       user-edn (assoc :user user-edn)
       project-edn (assoc :project project-edn)
       extra-edn (assoc :extra extra-edn)))))

;;;; deps edn manipulation

(defn- merge-or-replace
  "If maps, merge, otherwise replace"
  [& vals]
  (when (some identity vals)
    (reduce (fn [ret val]
              (if (and (map? ret) (map? val))
                (merge ret val)
                (or val ret)))
      nil vals)))

(defn merge-edns
  "Merge multiple deps edn maps from left to right into a single deps edn map."
  [deps-edn-maps]
  (apply merge-with merge-or-replace (remove nil? deps-edn-maps)))

;;;; Aliases

;; per-key binary merge-with rules

(def ^:private last-wins (comp last #(remove nil? %) vector))
(def ^:private append (comp vec concat))
(def ^:private append-unique (comp vec distinct concat))

(def ^:private merge-alias-rules
  {:deps merge ;; FUTURE: remove
   :replace-deps merge ;; formerly :deps
   :extra-deps merge
   :override-deps merge
   :default-deps merge
   :classpath-overrides merge
   :paths append-unique ;; FUTURE: remove
   :replace-paths append-unique ;; formerly :paths
   :extra-paths append-unique
   :jvm-opts append
   :main-opts last-wins
   :exec-fn last-wins
   :exec-args merge-or-replace
   :ns-aliases merge
   :ns-default last-wins})

(defn- choose-rule [alias-key val]
  (or (merge-alias-rules alias-key)
    (if (map? val)
      merge
      (fn [_v1 v2] v2))))

(defn merge-alias-maps
  "Like merge-with, but using custom per-alias-key merge function"
  [& ms]
  (reduce
    #(reduce
       (fn [m [k v]] (update m k (choose-rule k v) v))
       %1 %2)
    {} ms))

(defn combine-aliases
  "Find, read, and combine alias maps identified by alias keywords from
  a deps edn map into a single args map."
  [edn-map alias-kws]
  (->> alias-kws
    (map #(get-in edn-map [:aliases %]))
    (apply merge-alias-maps)))
