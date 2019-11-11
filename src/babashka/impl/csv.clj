(ns babashka.impl.csv
  {:no-doc true}
  (:require [babashka.impl.clojure.data.csv :as csv]))

(def csv-namespace
  {'read-record csv/read-record
   'read-csv csv/read-csv
   'write-record csv/write-record
   'write-csv csv/write-csv})
