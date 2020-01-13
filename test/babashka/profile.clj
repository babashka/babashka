(ns babashka.profile
  (:require [babashka.main :as main]))

(comment)

;; clojure -A:profile -e "(prn (loop [val 0 cnt 1000000] (if (pos? cnt) (recur (inc val) (dec cnt)) val)))"

#_(require '[clj-async-profiler.core :as prof])

(defn -main [& options]
  #_(prof/profile (apply main/main options))
  (shutdown-agents))
