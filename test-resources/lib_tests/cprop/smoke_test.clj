(ns cprop.smoke-test
  (:require [clojure.test :as t :refer [deftest is]]
            [cprop.core]
            [cprop.source :refer [from-env]]))

(deftest from-env-test
  (println (:cprop-env (from-env))))
