(ns babashka.exec-test
  (:require
   [babashka.test-utils :as u]
   [cheshire.core :as cheshire]
   [clojure.edn :as edn]
   [clojure.test :as t :refer [deftest is]]))

(defn bb [& args]
  (apply u/bb nil args))

(deftest exec-test
  (is (= {:foo 1} (edn/read-string (bb "-x" "prn" "--foo" "1"))))
  (is (thrown? Exception (bb "-x" "json/generate-string" "--foo" "1")))
  (is (= {:foo 1} (cheshire/parse-string
                   (edn/read-string
                    (bb "-x" "cheshire.core/generate-string" "--foo" "1")) true))))

(deftest tag-test
  (u/with-config
    "{:deps {}
      :tasks {foo (exec 'clojure.core/prn)}}"
    (is (= {:dude 1} (edn/read-string (bb "run" "foo" "--dude" "1"))))))
