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

(deftest dispatch-output-test
  (testing "with-out-str captures dispatch output"
    (is (= "pom\n"
           (u/normalize
            (edn/read-string
             (bb "-e" "(require '[babashka.cli :as cli])
                      (pr (with-out-str
                            (cli/dispatch {:spec {:skip {:enum [\"pom\"]}} :fn prn}
                                          [\"org.babashka.cli/completions\" \"complete\" \"--shell\" \"zsh\" \"--\" \"--skip\" \"\"]
                                          {:prog \"antq\"})))"))))))
  (testing "binding *err* captures dispatch errors"
    (is (re-find #"nope"
                 (edn/read-string
                  (bb "-e" "(require '[babashka.cli :as cli])
                            (pr (let [w (java.io.StringWriter.)]
                                  (binding [*err* w
                                            cli/*exit-fn* (fn [_])]
                                    (cli/dispatch {:cmd {\"add\" {:fn identity}}}
                                                  [\"nope\"] {:help true :prog \"t\"}))
                                  (str w)))"))))))
