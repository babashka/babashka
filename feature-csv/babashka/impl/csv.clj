(ns babashka.impl.csv
  {:no-doc true}
  (:require [clojure.data.csv :as csv]
            [sci.core :as sci]))

(def cns (sci/create-ns 'clojure.data.csv nil))

(def csv-namespace
  {'read-csv (sci/copy-var csv/read-csv cns)
   'write-csv (sci/copy-var csv/write-csv cns)})
