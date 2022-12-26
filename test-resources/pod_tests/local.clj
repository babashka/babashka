(ns pod-tests.local
  (:require [pod.test-pod :as pod]))

(defn -main [& args]
  (println (pod/add-sync 40 2)))
