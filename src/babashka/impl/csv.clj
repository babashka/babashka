(ns babashka.impl.csv
  {:no-doc true}
  (:require [clojure.data.csv :as csv]))

(def csv-namespace
  {'read-csv csv/read-csv
   'write-csv csv/write-csv})
