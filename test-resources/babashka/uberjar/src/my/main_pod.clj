(ns my.main-pod
  (:require [pod.babashka.go-sqlite3 :as sqlite]))

(defn -main [& _args]
  (sqlite/query ":memory:" ["SELECT 1 + 2 as sum"]))
