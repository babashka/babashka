(ns babashka.sci-test
  (:require
   [babashka.test-utils :as tu]
   [clojure.edn :as edn]
   [clojure.test :as t :refer [deftest is]]))

(deftest sci-test
  (is (= 1 (edn/read-string
            (tu/bb nil "-e" "
(ns foo)
(require '[sci.core :as sci])
(def x 1)
(def ctx (sci/init {:namespaces {'foo (sci/copy-ns foo (sci/create-ns 'foo))}}))
(sci/eval-string* ctx \"foo/x\")")))))
