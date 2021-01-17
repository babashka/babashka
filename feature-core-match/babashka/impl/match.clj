(ns babashka.impl.match
  {:no-doc true}
  (:require [clojure.core.match :as match]
            [sci.core :as sci :refer [copy-var]]))

(def mns (sci/create-ns 'clojure.core.match nil))

(def core-match-namespace
  {'match (copy-var match/match mns)
   'backtrack (copy-var match/backtrack mns)})
