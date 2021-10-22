(ns my.impl1
  (:require [clojure.string :as str]))

;; uberscript parser can parse and skip this
(prn ::str/foo)
str/join

(defn impl-fn
  "identity"
  [x] x)
