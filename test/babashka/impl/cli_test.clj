(ns babashka.impl.cli-test
  (:require
   [babashka.test-utils :as u]
   [clojure.edn :as edn]
   [clojure.test :as t :refer [deftest is testing]]))

(defn bb [& args]
  (apply u/bb nil args))

(deftest exit-fn-test
  (testing "*exit-fn* can be rebound from a script"
    (is (= {:exit 1 :cause :no-match}
           (edn/read-string
            (bb "-e" "(require '[babashka.cli :as cli])
                      (binding [cli/*exit-fn* (fn [m] (prn (select-keys m [:exit :cause])))]
                        (cli/dispatch {:cmd {\"add\" {:fn identity}}}
                                      [\"nope\"] {:help true :prog \"t\"}))"))))))
