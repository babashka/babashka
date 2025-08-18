(ns babashka.cheshire-test
  (:require
   [babashka.test-utils :as test-utils]
   [cheshire.core :as json]
   [clojure.test :as test :refer [deftest is]]))

(deftest cheshire-encode-test
  (is (instance? java.time.Instant
                 (java.time.Instant/parse
                  (:created-at
                   (json/parse-string
                    (test-utils/bb "-e"                                                                                                          "(require '[cheshire.core :as c]
                                    '[cheshire.generate :as cg]
                                    '[clojure.walk :as walk])
  (import (java.time Instant))

  (cg/add-encoder Instant
                  (fn [obj writer]
                    (cg/encode-str (str obj)  writer)))

  (def data {:created-at (Instant/now)})

  (println (c/generate-string data))")
                    true))))))
