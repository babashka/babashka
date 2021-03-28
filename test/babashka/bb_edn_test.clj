(ns babashka.bb-edn-test
  (:require
   [babashka.fs :as fs]
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is testing]]))

(defn bb [& args]
  (edn/read-string
   {:readers *data-readers*
    :eof nil}
   (apply test-utils/bb nil (map str args))))

(defmacro with-config [cfg & body]
  `(let [temp-dir# (fs/create-temp-dir)
         bb-edn-file# (fs/file temp-dir# "bb.edn")]
     (binding [*print-meta* true]
       (spit bb-edn-file# ~cfg))
     (binding [test-utils/*bb-edn-path* (str bb-edn-file#)]
       ~@body)))

(deftest doc-test
  (with-config {:tasks {:cool-task
                        {:doc "Usage: bb :cool-task

                          Addition is a pretty advanced topic.  Let us start with the identity element
                          0. ..."
                         :task ['babashka "-e" "(+ 1 2 3)"]}}}
    (is (str/includes? (apply test-utils/bb nil
                              (map str ["doc" :cool-task]))
                       "Usage: bb :cool-task"))))

(deftest main-task-test
  (with-config {:paths ["test-resources/task_scripts"]
                :tasks {:main-task ['main 'tasks 1 2 3]}}
    (is (= '("1" "2" "3") (bb :main-task)))
    (let [res (apply test-utils/bb nil
                     (map str ["doc" :main-task]))]
      (is (str/includes? res "Usage: just pass some args")))))

(deftest do-test
  (let [temp-dir (fs/create-temp-dir)
        temp-file (fs/create-file (fs/path temp-dir "temp-file.txt"))]
    (with-config {:tasks {:bye ['do
                                ['babashka "-e" "(+ 1 2 3)"]
                                ['shell "rm" (str temp-file)]]
                          :hello ['do
                                  ['babashka "-e" "(+ 1 2 3)"]
                                  [:bye]]}}
      (is (fs/exists? temp-file))
      (bb :hello)
      (is (not (fs/exists? temp-file))))))

(deftest clojure-test
  (let [temp-dir (fs/create-temp-dir)
        temp-file (fs/create-file (fs/path temp-dir "temp-file.txt"))]
    (with-config {:tasks {:jvm ['clojure "-M" "-e"
                                (format "(do (require '[clojure.java.io :as io])
                                             (.delete (io/file \"%s\")))" (str temp-file))]}}
      (is (fs/exists? temp-file))
      (bb :jvm)
      (is (not (fs/exists? temp-file))))))
