(ns mvn-oracle.oracle
  "Resolves one corpus entry with tools.deps and prints the result as EDN.
  Runs under bb and under clojure, so the two can be diffed."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.deps :as deps]
            [clojure.tools.deps.extensions :as ext]))

;; Root deps.edn, minus the clojure dep, so both sides see the same map.
(def standard-repos
  {"central" {:url "https://repo1.maven.org/maven2/"}
   "clojars" {:url "https://repo.clojars.org/"}})

(defn- relative-path [local-repo p]
  (let [root (str (.getCanonicalPath (io/file local-repo)) "/")]
    (if (str/starts-with? p root) (subs p (count root)) p)))

(defn- normalize-lib [local-repo [lib {:keys [mvn/version paths dependents exclusions] :as coord}]]
  [lib (cond-> {}
         version (assoc :mvn/version version)
         (:git/sha coord) (assoc :git/sha (:git/sha coord))
         paths (assoc :paths (vec (sort (map #(relative-path local-repo %) paths))))
         (seq dependents) (assoc :dependents (vec (sort dependents)))
         (seq exclusions) (assoc :exclusions (vec (sort exclusions))))])

(defn- coord-deps [config [lib coord]]
  (when (:mvn/version coord)
    (let [ds (ext/coord-deps lib coord (:deps/manifest coord) config)]
      [lib (vec (sort-by (comp str first) ds))])))

(defn- remote-only
  "Drops :local/root deps, they point into other repositories."
  [deps]
  (into {} (filter (fn [[_ c]] (or (:mvn/version c) (:git/sha c) (:git/url c)))) deps))

(defn- find-versions
  "The release versions of a top-level :mvn dep, as find-versions sees them.
  A set: versions that compare equal, 2.0-rc1 and 2.0.0-RC1, come out of
  Aether in hash order. The bb side checks its own ordering separately."
  [config [lib coord]]
  (when (:mvn/version coord)
    [lib (into (sorted-set) (map :mvn/version) (ext/find-versions lib coord :mvn config))]))

(defn run [{:keys [deps local-repo]}]
  (let [deps-map (-> deps
                     (update :deps remote-only)
                     (update :mvn/repos #(merge standard-repos %))
                     (assoc :mvn/local-repo local-repo))
        libs (deps/resolve-deps deps-map nil)]
    {:libs (into (sorted-map) (map #(normalize-lib local-repo %)) libs)
     :coord-deps (into (sorted-map) (keep #(coord-deps deps-map %)) libs)
     :find-versions (into (sorted-map) (keep #(find-versions deps-map %)) (:deps deps-map))}))

(defn -main [& [corpus-file entry-name local-repo]]
  (let [corpus (edn/read-string (slurp corpus-file))
        entry (some #(when (= entry-name (:name %)) %) corpus)
        _ (assert entry (str "no corpus entry " entry-name))
        deps (if-let [f (:deps-file entry)]
               (edn/read-string (slurp (io/file (.getParentFile (io/file corpus-file)) f)))
               entry)
        deps (select-keys deps [:deps :mvn/repos])]
    (binding [*print-namespace-maps* false]
      (pp/pprint (run {:deps deps :local-repo local-repo})))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
