(ns cider.nrepl.version
  (:require
   [clojure.java.io :as io]))

(def version-string
  "The current version for cider-nrepl as a string."
  ;; BB-PATCH the version.edn resource is not in the image
  "0.64.0"
  #_(-> (io/resource "cider/nrepl/version.edn")
      slurp
      read-string))

(assert (seq version-string))

(assert (string? version-string))

(def version
  "Current version of CIDER nREPL as a map.
  Map of :major, :minor, :incremental, :qualifier,
  and :version-string."
  (assoc (->> version-string
              (re-find #"(\d+)\.(\d+)\.(\d+)-?(.*)")
              rest
              (map #(try (Integer/parseInt %) (catch Exception _e nil)))
              (zipmap [:major :minor :incremental :qualifier]))
         :version-string version-string))
