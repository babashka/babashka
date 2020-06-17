(ns babashka.run-all-libtests
  (:require [clojure.test :as t]))

(def status (atom {}))

(defn test-namespaces [& namespaces]
  (let [m (apply t/run-tests namespaces)]
    (swap! status (fn [status]
                    (merge-with + status m)))))

;;;; Minimallist

(require '[minimallist.core-test])

(test-namespaces 'minimallist.core-test)

;;;; Final exit code

(let [{:keys [:test :fail :error] :as m} @status]
  (prn m)
  (when-not (pos? test)
    (System/exit 1))
  (System/exit (+ fail error)))
