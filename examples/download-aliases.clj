#!/usr/bin/env bb

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def edn (edn/read-string (slurp "deps.edn")))

(def aliases (keys (:aliases edn)))

(require '[babashka.deps :as deps])

(def cmd ["-P" (str "-A" (str/join aliases))])

(println "Downloading deps using:" cmd)

(deps/clojure cmd)
