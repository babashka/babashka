(ns babashka.impl.priority-map
  (:require [clojure.data.priority-map :as pm]
            [sci.core :as sci]))

(def pmns (sci/create-ns 'clojure.data.priority-map))

(def priority-map-namespace
  {'priority-map (sci/copy-var pm/priority-map pmns)
   'priority-map-keyfn (sci/copy-var pm/priority-map-keyfn pmns)
   'subseq (sci/copy-var pm/subseq pmns)
   'rsubseq (sci/copy-var pm/rsubseq pmns)})
