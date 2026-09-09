;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.tools.deps.extensions
  "Defines the SPI for new dep and manifest types - to implement, extend the
  multimethods here, then load those extensions before using the tools.deps API."
  (:require
    [clojure.java.io :as jio]
    [clojure.set :as set]))

;; Helper for autodetect of manifest type

;; vector to control ordering
(def manifest-types
  ["deps.edn" :deps,
   "pom.xml" :pom
   ;; "project.clj" :lein
   ])

(defn detect-manifest
  "Given a directory, detect the manifest type and return the manifest info."
  [dir]
  (loop [[file-name manifest-type & others] manifest-types]
    (when file-name
      (let [f (jio/file dir file-name)]
        (if (and (.exists f) (.isFile f))
          {:deps/manifest manifest-type, :deps/root dir}
          (recur others))))))

;; Methods switching on coordinate type

(defmulti coord-type-keys
  "Takes a coordinate type and returns valid set of keys indicating that coord type"
  (fn [type] type))

(defmethod coord-type-keys :default [type] #{})

(defn procurer-types
  "Returns set of registerd procurer types (results may change if procurer methods are registered)."
  []
  (disj (-> (.getMethodTable ^clojure.lang.MultiFn coord-type-keys) keys set) :default))

(defn coord-type
  "Determine the coordinate type of the coordinate, based on the self-published procurer
  keys from coord-type-keys."
  [coord]
  (when (map? coord)
    (let [exts (procurer-types)
          coord-keys (-> coord keys set)
          matches (reduce (fn [ms type]
                            (cond-> ms
                              (seq (set/intersection (coord-type-keys type) coord-keys))
                              (conj type)))
                    [] exts)]
      (case (count matches)
        0 (throw (ex-info (format "Coord of unknown type: %s" (pr-str coord)) {:coord coord}))
        1 (first matches)
        (throw (ex-info (format "Coord type is ambiguous: %s" (pr-str coord)) {:coord coord}))))))

(defmulti lib-location
  "Takes a coordinate and returns the location where the lib would be
  installed locally. Location keys:

  :base     local repo base directory path
  :path     path within base dir
  :type     coordinate type"
  (fn [lib coord config] (coord-type coord)))

(defmulti canonicalize
  "Takes a lib and coordinate and returns a canonical form.
  For example, a Maven coordinate might resolve LATEST to a version or a Git
  coordinate might resolve a partial sha to a full sha. Returns [lib coord]."
  (fn [lib coord config] (coord-type coord)))

(defmethod canonicalize :default [lib coord config]
  [lib coord])

(defmulti dep-id
  "Returns an identifier value that can be used to detect a lib/coord cycle while
   expanding deps. This will only be called after canonicalization so it can rely
   on the canonical form."
  (fn [lib coord config] (coord-type coord)))

(defn- throw-bad-coord
  [lib coord]
  (if (map? coord)
    (let [type (coord-type coord)]
      (if (nil? type)
        (throw (ex-info (str "No coordinate type found for library " lib " in coordinate " (pr-str coord)) {:lib lib :coord coord}))
        (throw (ex-info (str "Coordinate type " type " not loaded for library " lib " in coordinate " (pr-str coord))
                 {:lib lib :coord coord}))))
    (throw (ex-info (str "Bad coordinate for library " lib ", expected map: " (pr-str coord)) {:lib lib :coord coord}))))

(defmethod dep-id :default [lib coord config]
  (throw-bad-coord lib coord))

(defmulti manifest-type
  "Takes a lib, a coord, and the root config. Dispatch based on the
   coordinate type. Detect and return the manifest type and location
   for this coordinate."
  (fn [lib coord config] (coord-type coord)))

(defmethod manifest-type :default [lib coord config]
  (throw-bad-coord lib coord))

(defmulti coord-summary
  "Takes a coord, and returns a concise description, used when printing tree"
  (fn [lib coord] (coord-type coord)))

(defmethod coord-summary :default [lib coord]
  (str lib " " (coord-type coord)))

(defmulti license-info
  "Return map of license info (:name and :url) or nil if unknown"
  (fn [lib coord config] (coord-type coord)))

(defmethod license-info :default [lib coord config] nil)

;; Version comparison, either within or across coordinate types

(defmulti compare-versions
  "Given two coordinates, use this as a comparator returning a negative number, zero,
  or positive number when coord-x is logically 'less than', 'equal to', or 'greater than'
  coord-y. The dispatch occurs on the type of x and y."
  (fn [lib coord-x coord-y config] [(coord-type coord-x) (coord-type coord-y)]))

(defmethod compare-versions :default
  [lib coord-x coord-y config]
  (throw (ex-info (str "Unable to compare versions for " lib ": " (pr-str coord-x) " and " (pr-str coord-y))
           {:lib lib :coord-x coord-x :coord-y coord-y})))

;; Find coords

(defmulti find-versions
  "Return a coll of coords based on a lib and a partial coord"
  (fn [lib coord coord-type config] coord-type))

(defmethod find-versions :default [lib coord coord-type config]
  (throw-bad-coord lib coord))

(defn find-all-versions
  "Find versions across all registered procurer types.
  Returns coll of coordinates for this lib (based on lib and partial coordinate).
  For each procurer type, coordinates are returned in chronological order."
  [lib coord config]
  (mapcat #(find-versions lib coord % config) (procurer-types)))

;; Methods switching on manifest type

(defn- throw-bad-manifest
  [lib coord manifest-type]
  (if manifest-type
    (throw (ex-info (str "Manifest type " manifest-type " not loaded for " lib " in coordinate " (pr-str coord))
             {:lib lib :coord coord}))
    (throw (ex-info (str "Manifest file not found for " lib " in coordinate " (pr-str coord))
             {:lib lib :coord coord}))))

(defmulti coord-deps
  "Return coll of immediate [lib coord] external deps for this library."
  (fn [lib coord manifest-type config] manifest-type))

(defmethod coord-deps :default [lib coord manifest-type config]
  (throw-bad-manifest lib coord manifest-type))

(defmulti coord-paths
  "Return coll of classpath roots for this library on disk."
  (fn [lib coord manifest-type config] manifest-type))

(defmethod coord-paths :default [lib coord manifest-type config]
  (throw-bad-manifest lib coord manifest-type))

(defmulti manifest-file
  "Return path to manifest file (if any). If this file is updated,
  causes the cache to be recomputed."
  (fn [lib coord manifest-type config] manifest-type))

(defmethod manifest-file :default [lib coord manifest-type config]
  (throw-bad-manifest lib coord manifest-type))

(defmulti license-info-mf
  "Return map of license info (:name and :url) or nil if unknown
  based on the manifest."
  (fn [lib coord manifest-type config] manifest-type))

(defmethod license-info-mf :default [lib coord manifest-type config] nil)

(defmulti coord-usage
  "Return usage info map for this library with the following optional keys:
    :ns-default - default namespace symbol
    :ns-aliases - map of alias to namespace symbol"
  (fn [lib coord manifest-type config] manifest-type))

(defmethod coord-usage :default [lib coord manifest-type config]
  (throw-bad-manifest lib coord manifest-type))

(defmulti prep-command
  "Return prep command for this library with the following keys:
    :alias - alias to use when invoking (keyword)
    :fn - function to invoke in alias (symbol)
    :ensure - relative path in repo to ensure exists after prep"
  (fn [lib coord manifest-type config] manifest-type))

(defmethod prep-command :default [lib coord manifest-type config]
  (throw-bad-manifest lib coord manifest-type))

(comment
  (require
    '[clojure.tools.deps.extensions.maven]
    '[clojure.tools.deps.extensions.git]
    '[clojure.tools.deps.util.maven :as maven])

  (binding [*print-namespace-maps* false]
    (run! prn
      (find-all-versions 'io.github.clojure/tools.deps.alpha nil {:mvn/repos maven/standard-repos})))

  (binding [*print-namespace-maps* false]
    (run! prn
      (find-all-versions 'io.github.clojure/tools.build nil {:mvn/repos maven/standard-repos})))


  )