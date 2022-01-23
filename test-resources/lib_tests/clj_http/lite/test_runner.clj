(ns clj-http.lite.test-runner
  (:require [clj-http.lite.client-test]
            [clojure.test :as t]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'clj-http.lite.client-test)]
    (System/exit (if (or (pos? fail)
                         (pos? error))
                   1 0))))

