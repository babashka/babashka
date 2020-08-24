(ns babashka.file-location1
  (:require [babashka.classpath :as cp]))

(cp/add-classpath "test-resources")

(require '[babashka.file-location2 :as f2])

(f2/uh-oh)
