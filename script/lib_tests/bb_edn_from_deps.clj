(ns bb_edn_from_deps
  (:require [clojure.edn :as edn]
            [clojure.set :as set]))

(defn select-deps [m] (select-keys m [:paths :deps]))

(defn extra-deps [m]
  (-> m
    (get-in [:aliases :lib-tests])
    (set/rename-keys {:extra-deps  :deps
                      :extra-paths :paths})
    select-deps))

(if (seq *command-line-args*)
  (->> (slurp "deps.edn")
    edn/read-string
    ((juxt select-deps extra-deps))
    (apply merge-with into)
    (spit (first *command-line-args*)))
  (println "Please specify an output file"))
