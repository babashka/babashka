(ns babashka.bb-edn-test
  {:clj-kondo/config '{:linters {:unresolved-symbol {:exclude [working?]}}}}
  (:require
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is testing *report-counters*]]
   [flatland.ordered.map :refer [ordered-map]]
   [sci.core :as sci])
  )

(defn bb [input & args]
  (edn/read-string
   {:readers *data-readers*
    :eof nil}
   (apply test-utils/bb (when (some? input) (str input)) (map str args))))

(deftest foobar-test
  (prn :foobar))

