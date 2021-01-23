(ns babashka.impl.match
  {:no-doc true}
  (:require [clojure.core.match :as match]
            clojure.core.match.array ;; side effecting
            clojure.core.match.debug ;; side effecting
            clojure.core.match.protocols ;; side effecting
            clojure.core.match.regex ;; side effecting
            [sci.core :as sci :refer [copy-var]]))

(def mns (sci/create-ns 'clojure.core.match nil))

(def core-match-namespace
  {'match (copy-var match/match mns)
   'backtrack (copy-var match/backtrack mns)
   'val-at*   (copy-var match/val-at* mns)
   'defpred   (copy-var match/defpred mns)})
