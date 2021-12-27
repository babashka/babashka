(ns babashka.run-all-libtests
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.test :as t]))

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

(def windows? (-> (System/getProperty "os.name")
                (str/lower-case)
                (str/includes? "win")))

;;;; cprop

;; TODO: port to test-namespaces

(require '[cprop.core])
(require '[cprop.source :refer [from-env]])
(println (:cprop-env (from-env)))

;;;; clojure.data.zip

;; TODO: port to test-namespaces

(require '[clojure.data.xml :as xml])
(require '[clojure.zip :as zip])
(require '[clojure.data.zip.xml :refer [attr attr= xml1->]])

(def data (str "<root>"
               "  <character type=\"person\" name=\"alice\" />"
               "  <character type=\"animal\" name=\"march hare\" />"
               "</root>"))

;; TODO: convert to test
(let [xml   (zip/xml-zip (xml/parse (java.io.StringReader. data)))]
                                        ;(prn :xml xml)
  (prn :alice-is-a (xml1-> xml :character [(attr= :name "alice")] (attr :type)))
  (prn :animal-is-called (xml1-> xml :character [(attr= :type "animal")] (attr :name))))

;;;; deps.clj

;; TODO: port to test-namespaces

(require '[babashka.curl :as curl])
(spit "deps_test.clj"
      (:body (curl/get "https://raw.githubusercontent.com/borkdude/deps.clj/master/deps.clj"
               (if windows? {:compressed false} {}))))

(binding [*command-line-args* ["-Sdescribe"]]
  (load-file "deps_test.clj"))

(.delete (io/file "deps_test.clj"))

;;;; doric

(defn test-doric-cyclic-dep-problem
  []
  (require '[doric.core :as d])
  ((resolve 'doric.core/table) [:a :b] [{:a 1 :b 2}]))

(when (test-namespace? 'doric.test.core)
  (test-doric-cyclic-dep-problem))

;;;; babashka.process
(when-not windows?
  ;; test built-in babashka.process
  (test-namespaces 'babashka.process-test)

  ;; test babashka.process from source
  (require '[babashka.process] :reload)
  (test-namespaces 'babashka.process-test))

(let [lib-tests (edn/read-string (slurp (io/resource "bb-tested-libs.edn")))]
  (doseq [{tns :test-namespaces skip-windows :skip-windows} (vals lib-tests)]
    (when-not (and skip-windows windows?)
      (apply test-namespaces tns))))

;;;; final exit code

(let [{:keys [:test :fail :error] :as m} @status]
  (prn m)
  (when-not (pos? test)
    (System/exit 1))
  (System/exit (+ fail error)))
