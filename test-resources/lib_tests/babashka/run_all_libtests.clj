(ns babashka.run-all-libtests
  (:require [babashka.core :refer [windows?]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :as t :refer [*report-counters*]]))

(defmethod clojure.test/report :end-test-var [_m]
  (when-let [rc *report-counters*]
    (let [{:keys [:fail :error]} @rc]
      (when (and (= "true" (System/getenv "BABASHKA_FAIL_FAST"))
                 (or (pos? fail) (pos? error)))
        (println "=== Failing fast")
        (System/exit 1)))))

(def ns-args (set (map symbol *command-line-args*)))

(def status (atom {}))

(defn test-namespace? [ns]
  (or (empty? ns-args)
      (contains? ns-args ns)))

(defn test-namespaces [& namespaces]
  (let [namespaces (seq (filter test-namespace? namespaces))]
    (when (seq namespaces)
      (doseq [n namespaces]
        (require n))
      (let [m (apply t/run-tests namespaces)]
        (swap! status (fn [status]
                        (merge-with + status (dissoc m :type))))))))

;; Standard test-runner for libtests
(let [lib-tests (edn/read-string (slurp (io/resource "bb-tested-libs.edn")))]
  (doseq [{tns :test-namespaces skip-windows :skip-windows} (vals lib-tests)]
    (when-not (and skip-windows (windows?))
      (apply test-namespaces tns))))

;; Non-standard tests - These are tests with unusual setup around test-namespaces

;;;; doric

(defn test-doric-cyclic-dep-problem
  []
  (require '[doric.core :as d])
  ((resolve 'doric.core/table) [:a :b] [{:a 1 :b 2}]))

(when (test-namespace? 'doric.test.core)
  (test-doric-cyclic-dep-problem))

;;;; babashka.process
(when-not (windows?)
  ;; test built-in babashka.process
  (test-namespaces 'babashka.process-test)

  ;; test babashka.process from source
  (require '[babashka.process] :reload)
  (test-namespaces 'babashka.process-test))

;;;; final exit code

(let [{:keys [:test :fail :error] :as m} @status]
  (prn m)
  (when-not (pos? test)
    (System/exit 1))
  (System/exit (+ fail error)))
