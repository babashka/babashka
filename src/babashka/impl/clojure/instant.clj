(ns babashka.impl.clojure.instant
  (:require [clojure.instant :as i]
            [sci.core :as sci]))

(def ins (sci/create-ns 'clojure.instant nil))

(def instant-namespace
  {'read-instant-date (sci/copy-var i/read-instant-date ins)})
