(ns babashka.run-all-libtests
  (:require
   [babashka.classpath :as cp :refer [add-classpath]]
   [babashka.core :refer [windows?]]
   [babashka.fs :as fs]
   [babashka.process :refer [sh]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.spec.test.alpha :as st]
   [clojure.string :as str]
   [clojure.test :as t :refer [*report-counters*]]))

(def orig-spec-checking-fn @#'st/spec-checking-fn)

(defmethod t/report :end-test-var [_m]
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

(defn- filter-vars!
  [ns filter-fn]
  (doseq [[_name var] (ns-publics ns)]
    (when (:test (meta var))
      (when (not (filter-fn var))
        (alter-meta! var #(-> %
                              (assoc ::test (:test %))
                              (dissoc :test)))))))

(defn test-namespaces [& namespaces]
  (let [namespaces (seq (filter test-namespace? namespaces))]
    (when (seq namespaces)
      (let [namespaces namespaces]
        (doseq [n namespaces]
          (let [orchestra? (str/starts-with? (str n) "orchestra")]
            (if orchestra?
              nil ;; (alter-var-root #'st/spec-checking-fn (constantly ot/spec-checking-fn))
              (alter-var-root #'st/spec-checking-fn (constantly orig-spec-checking-fn)))
            (when-not orchestra?
              (require n)
              (filter-vars! (find-ns n) #(-> % meta ((some-fn :skip-bb
                                                              :test-check-slow)) not))
              (let [m (apply t/run-tests [n])]
                (swap! status (fn [status]
                                (merge-with + status (dissoc m :type))))))))))))

(defn current-branch []
  (or (System/getenv "APPVEYOR_PULL_REQUEST_HEAD_REPO_BRANCH")
      (System/getenv "APPVEYOR_REPO_BRANCH")
      (System/getenv "CIRCLE_BRANCH")
      (System/getenv "GITHUB_REF_NAME")
      (System/getenv "CIRRUS_BRANCH")
      (-> (sh "git" "rev-parse" "--abbrev-ref" "HEAD")
          :out
          str/trim)))

;; Standard test-runner for libtests
(let [lib-tests (edn/read-string (slurp (io/resource "bb-tested-libs.edn")))
      test-nss (atom [])]
  (doseq [[libname {tns :test-namespaces skip-windows :skip-windows
                    :keys [test-paths
                           git-sha flaky]}] lib-tests]
    (let [git-dir (format ".gitlibs/libs/%s/%s" libname git-sha)
          git-dir (fs/file (fs/home) git-dir)]
      (doseq [p test-paths]
        (add-classpath (str (fs/file git-dir p)))))
    (when-not (and skip-windows (windows?))
      (if (and flaky (#{"main" "master"} (current-branch)))
        (println "Skipping" tns "for main branch because it's marked flaky")
        (swap! test-nss into tns))))
  (apply test-namespaces @test-nss))

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
