(ns babashka.yaml-test
  (:require [babashka.test-utils :as test-utils]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def simple-yaml-str "topic: [point 1, point 2]")

(deftest yaml-edn-read-test
  (let [parsed-edn   (test-utils/bb nil (str "(yaml/parse-string \"" simple-yaml-str "\")"))
        emitted-yaml (test-utils/bb parsed-edn "(yaml/generate-string *input*)")]
    (is (every? #(str/includes? emitted-yaml %) ["topic:" "point 1" "point 2"]))))

(def round-trip-prog
  (str "(yaml/generate-string (read-string (pr-str (yaml/parse-string \"" simple-yaml-str "\"))))"))

(deftest yaml-data-readers-test
  (let [emitted-yaml (test-utils/bb nil round-trip-prog)]
    (is (every? #(str/includes? emitted-yaml %) ["topic:" "point 1" "point 2"]))))
