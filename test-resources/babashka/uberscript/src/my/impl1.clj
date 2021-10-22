(ns my.impl1
  (:require [babashka.pods :as pods]
            [clojure.string :as str]))

;; uberscript parser can parse and skip this
(prn ::str/foo)
str/join

(alias 'a 'clojure.string)
::a/foo ;; no error either

(pods/load-pod 'clj-kondo/clj-kondo "2021.10.19")
(require '[pod.borkdude.clj-kondo :as clj-kondo])

(prn (some? clj-kondo/run!))

(defn impl-fn
  "identity"
  [x] x)
