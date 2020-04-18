(ns babashka.transit-test
  (:require
   [babashka.test-utils :as test-utils]
   [clojure.java.io :as io]
   [clojure.test :as t :refer [deftest is]]))

(defn bb [& args]
  (apply test-utils/bb nil (map str args)))

(deftest transit-test
  (is (= "\"foo\"\n{:a [1 2]}\n"
         (bb (format "(load-file \"%s\")"
                     (.getPath (io/file "test-resources" "babashka" "transit.clj")))))))
