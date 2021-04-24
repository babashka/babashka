(ns babashka.bb-edn-test
  (:require
   [babashka.fs :as fs]
   [babashka.impl.common :as common]
   [babashka.main :as main]
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is testing]]))

(defn bb [& args]
  (edn/read-string
   {:readers *data-readers*
    :eof nil}
   (apply test-utils/bb nil (map str args))))

(deftest doc-test
  (test-utils/with-config {:paths ["test-resources/task_scripts"]}
    (is (str/includes? (apply test-utils/bb nil
                              (map str ["doc" "tasks"]))
                       "This is task ns docstring."))
    (is (str/includes? (apply test-utils/bb nil
                              (map str ["doc" "tasks/foo"]))
                       "Foo docstring"))
    (is (str/includes? (apply test-utils/bb nil
                              (map str ["doc" "tasks/-main"]))
                       "Main docstring"))))

(deftest deps-test
  (test-utils/with-config '{:deps {medley/medley {:mvn/version "1.3.0"}}}
    (is (= '{1 {:id 1}, 2 {:id 2}}
           (bb "-e" "(require 'medley.core)" "-e" "(medley.core/index-by :id [{:id 1} {:id 2}])"))))
  (testing "--classpath option overrides bb.edn"
    (test-utils/with-config '{:deps {medley/medley {:mvn/version "1.3.0"}}}
      (is (= "src"
             (bb "-cp" "src" "-e" "(babashka.classpath/get-classpath)"))))))

(deftest task-test
  (test-utils/with-config '{:tasks {foo (+ 1 2 3)}}
    (is (= 6 (bb "foo"))))
  (let [tmp-dir (fs/create-temp-dir)
        out (str (fs/file tmp-dir "out.txt"))]
    (testing "shell test"
      (test-utils/with-config {:tasks {'foo (list 'shell {:out out}
                                                  "echo hello")}}
        (bb "foo")
        (is (= "hello\n" (slurp out)))))
    (fs/delete out)
    (testing "clojure test"
      (test-utils/with-config {:tasks {'foo (list 'clojure {:out out}
                                                  "-M -e" "(println :yolo)")}}
        (bb "foo")
        (is (= ":yolo\n" (slurp out)))))
    (fs/delete out)
    (testing "depends"
      (test-utils/with-config {:tasks {'quux (list 'spit out "quux\n")
                                       'baz (list 'spit out "baz\n" :append true)
                                       'bar {:depends ['baz]
                                             :task (list 'spit out "bar\n" :append true)}
                                       'foo {:depends ['quux 'bar 'baz]
                                             :task (list 'spit out "foo\n" :append true)}}}
        (bb "foo")
        (is (= "quux\nbaz\nbar\nfoo\n" (slurp out)))))
    (fs/delete out)
    (testing "init test"
      (test-utils/with-config '{:tasks {:init (def x 1)
                                        foo x}}
        (is (= 1 (bb "foo")))))
    (testing "requires test"
      (test-utils/with-config '{:tasks {:requires ([babashka.fs :as fs])
                                        foo (fs/exists? ".")}}
        (is (= true (bb "foo"))))
      (test-utils/with-config '{:tasks {foo {:requires ([babashka.fs :as fs])
                                             :task (fs/exists? ".")}}}
        (is (= true (bb "foo"))))
      (test-utils/with-config '{:tasks {bar {:requires ([babashka.fs :as fs])}
                                        foo {:depends [bar]
                                             :task (fs/exists? ".")}}}
        (is (= true (bb "foo")))))
    ;; This is why we don't support :when for now
    #_(testing "depends with :when"
      (test-utils/with-config {:tasks {'quux (list 'spit out "quux\n")
                                       'baz (list 'spit out "baz\n" :append true)
                                       'bar {:when false
                                             :depends ['baz]
                                             :task (list 'spit out "bar\n" :append true)}
                                       'foo {:depends ['quux 'bar]
                                             :task (list 'spit out "foo\n" :append true)}}}
        (bb "foo")
        (is (= "quux\nbaz\nbar\nfoo\n" (slurp out)))))
    (testing "map returned from task"
      (test-utils/with-config '{:tasks {foo {:task {:a 1 :b 2}}}}
        (is (= {:a 1 :b 2} (bb "foo")))))))

(deftest list-tasks-test
  (test-utils/with-config {}
    (let [res (test-utils/bb nil "tasks")]
      (is (str/includes? res "No tasks found."))))
  (test-utils/with-config {:paths ["test-resources/task_scripts"]
                           :tasks {'task1
                                   {:doc "task1 doc"
                                    :task '(+ 1 2 3)}
                                   'task2
                                   {:doc "task2 doc"
                                    :task '(+ 4 5 6)}
                                   '-task3
                                   {:task '(+ 1 2 3)}
                                   'task4
                                   {:task '(+ 1 2 3)
                                    :private true}
                                   'foo 'tasks/foo
                                   'bar 'non-existing/bar}}
    (let [res (test-utils/bb nil "tasks")]
      (is (= "The following tasks are available:\n\nbar  \nfoo   Foo docstring\ntask1 task1 doc\ntask2 task2 doc\n"
             res)))))

(deftest task-priority-test
  (when-not test-utils/native?
    (testing "FILE > TASK > SUBCOMMAND"
      (is (= "foo.jar" (:uberjar (main/parse-opts ["uberjar" "foo.jar"]))))
      (vreset! common/bb-edn '{:tasks {uberjar (+ 1 2 3)}})
      (is (= "uberjar" (:run (main/parse-opts ["uberjar"]))))
      (try
        (spit "uberjar" "#!/usr/bin/env bb\n(+ 1 2 3)")
        (vreset! common/bb-edn '{:tasks {uberjar (+ 1 2 3)}})
        (is (= "uberjar" (:file (main/parse-opts ["uberjar"]))))
        (finally (fs/delete "uberjar"))))))

(deftest min-bb-version
  (when-not test-utils/native?
    (vreset! common/bb-edn '{:min-bb-version "300.0.0"})
    (let [sw (java.io.StringWriter.)]
      (binding [*err* sw]
        (main/main "-e" "nil"))
      (is (str/includes? (str sw)
                         "WARNING: this project requires babashka 300.0.0 or newer, but you have: ")))))

;; TODO:
;; Do we want to support the same parsing as the clj CLI?
;; Or do we want `--aliases :foo:bar`
;; Let's wait for a good use case
#_(deftest alias-deps-test
  (test-utils/with-config '{:aliases {:medley {:deps {medley/medley {:mvn/version "1.3.0"}}}}}
    (is (= '{1 {:id 1}, 2 {:id 2}}
           (bb "-A:medley" "-e" "(require 'medley.core)" "-e" "(medley.core/index-by :id [{:id 1} {:id 2}])")))))
