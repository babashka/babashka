#!/usr/bin/env bb

(require '[clojure.edn :as edn])
(require '[clojure.java.shell :refer [sh]])
(require '[clojure.string :as str])

(def deps (-> (slurp (or (first *command-line-args*)
                         "deps.edn"))
              edn/read-string
              :deps))
(def with-release (zipmap (keys deps)
                          (map #(assoc % :mvn/version "RELEASE")
                               (vals deps))))

(defn deps->versions [deps]
  (let [res (sh "clojure" "-Sdeps" (str {:deps deps}) "-Stree")
        tree (:out res)
        lines (str/split tree #"\n")
        top-level (remove #(str/starts-with? % " ") lines)
        deps (map #(str/split % #" ") top-level)]
    (reduce (fn [acc [dep version]]
              (assoc acc dep version))
            {}
            deps)))

(def version-map (deps->versions deps))
(def new-version-map (deps->versions with-release))

(doseq [[dep version] version-map
        :let [new-version (get new-version-map dep)]
        :when (not= version new-version)]
  (println dep "can be upgraded from" version "to" new-version))

;; Inspired by an idea from @seancorfield on Clojurians Slack
