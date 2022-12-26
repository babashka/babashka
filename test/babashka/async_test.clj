(ns babashka.async-test
  (:require
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.test :as t :refer [deftest is]]))

(deftest ^:skip-windows alts!!-test
  (is (= "process 2\n" (test-utils/bb nil "
   (defn async-command [& args]
     (async/thread (apply shell/sh \"bash\" \"-c\" args)))

   (-> (async/alts!! [(async-command \"sleep 2 && echo process 1\")
                      (async-command \"sleep 1 && echo process 2\")])
     first :out str/trim println)"))))

(deftest go-test
  (is (number? (edn/read-string (test-utils/bb nil "
(defn calculation-go []
  (async/go
    ;; wait for some stuff
    (rand-int 1000)))

(defn get-result-go []
  (async/go
    (->>
     (repeatedly 10 calculation-go)
     (map async/<!)
     (reduce +))))

(async/<!! (get-result-go))")))))

(deftest binding-conveyance-test
  (is (number? (edn/read-string (test-utils/bb nil "
(def ^:dynamic x 0)
(binding [x 10] (async/<!! (async/thread x)))")))))

(deftest alts-test
  (is (true? (edn/read-string (test-utils/bb nil "
(= 10 (first (async/<!!
  (async/go
    (async/alts!
     [(async/go
        (async/<! (async/timeout 100))
        10)])))))")))))
