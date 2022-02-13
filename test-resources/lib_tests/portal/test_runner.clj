(ns portal.test-runner
  (:require [clojure.test :refer [run-tests]]
            [portal.jvm-test]
            [portal.runtime.cson-test]
            [portal.runtime.fs-test]))

(defn -main []
  (let [{:keys [fail error]}
        (run-tests 'portal.jvm-test
                   'portal.runtime.cson-test
                   'portal.runtime.fs-test)]
    (shutdown-agents)
    (System/exit (+ fail error))))

