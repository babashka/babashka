(ns babashka.bb-edn-test
  (:require
   [babashka.fs :as fs]
   [babashka.impl.classpath :as cp]
   [babashka.impl.common :as common]
   [babashka.main :as main]
   [babashka.test-utils :as test-utils]
   [borkdude.deps]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is testing]]))

(defn bb [& args]
  (let [args (map str args)
        ret (apply test-utils/bb nil args)]
    ;; (.println System/out :args)
    ;; (.println System/out (vec args))
    ;; (.println System/out :ret)
    ;; (.println System/out ret)
    (edn/read-string
     {:readers *data-readers*
      :eof nil}
     ret)))

(deftest deps-test
  (test-utils/with-config '{:deps {medley/medley {:mvn/version "1.3.0"}}}
    (is (= '{1 {:id 1}, 2 {:id 2}}
           (bb "-e" "(require 'medley.core)" "-e" "(medley.core/index-by :id [{:id 1} {:id 2}])"))))
  (test-utils/with-config '{:deps {medley/medley {:mvn/version "1.3.0"}}}
    (let [cp (bb "-e" "(do (require '[babashka.classpath :as cp])
                           (cp/split-classpath (cp/get-classpath)))")]
      (is (= 1 (count cp)))
      (is (str/includes? (first cp) "medley"))))
  (testing "--classpath option overrides bb.edn"
    (test-utils/with-config '{:deps {medley/medley {:mvn/version "1.3.0"}}}
      (is (= "src"
             (bb "-cp" "src" "-e" "(babashka.classpath/get-classpath)"))))))

(deftest print-deps-test
  (test-utils/with-config '{:deps {medley/medley {:mvn/version "1.3.0"}}}
    (testing "deps output"
      (let [edn (bb "print-deps")
            deps (:deps edn)]
        (is deps)
        (is (map? (get deps 'selmer/selmer)))
        (is (string? (:mvn/version (get deps 'selmer/selmer))))
        (testing "user provided lib"
          (is (map? (get deps 'medley/medley))))))
    (testing "classpath output"
      (let [classpath (test-utils/bb nil "print-deps" "--format" "classpath")]
        (is (str/includes? classpath "selmer"))
        (is (str/includes? classpath (System/getProperty "path.separator")))
        (is (str/includes? classpath "medley"))))))

(deftest task-test
  (test-utils/with-config '{:tasks {foo (+ 1 2 3)}}
    (is (= 6 (bb "run" "--prn" "foo"))))
  (testing "init test"
    (test-utils/with-config '{:tasks {:init (def x 1)
                                      foo   x}}
      (is (= 1 (bb "run" "--prn" "foo")))))
  (testing "requires test"
    (test-utils/with-config '{:tasks {:requires ([babashka.fs :as fs])
                                      foo       (fs/exists? ".")}}
      (is (= true (bb "run" "--prn" "foo"))))
    (test-utils/with-config '{:tasks {foo {:requires ([babashka.fs :as fs])
                                           :task     (fs/exists? ".")}}}
      (is (= true (bb "run" "--prn" "foo"))))
    (test-utils/with-config '{:tasks {bar {:requires ([babashka.fs :as fs])}
                                      foo {:depends [bar]
                                           :task    (fs/exists? ".")}}}
      (is (= true (bb "run" "--prn" "foo")))))
  (testing "map returned from task"
    (test-utils/with-config '{:tasks {foo {:task {:a 1 :b 2}}}}
      (is (= {:a 1 :b 2} (bb "run" "--prn" "foo")))))
  (let [tmp-dir  (fs/create-temp-dir)
        out      (str (fs/file tmp-dir "out.txt"))
        echo-cmd (if main/windows? "cmd /c echo" "echo")
        ls-cmd   (if main/windows? "cmd /c dir" "ls")
        fix-lines test-utils/normalize]
    (testing "shell test"
      (test-utils/with-config {:tasks {'foo (list 'shell {:out out}
                                                  echo-cmd "hello")}}
        (bb "foo")
        (is (= "hello\n" (fix-lines (slurp out))))))
    (fs/delete out)
    (testing "shell test with :continue fn"
      (test-utils/with-config {:tasks {'foo (list '-> (list 'shell {:out      out
                                                                    :err      out
                                                                    :continue '(fn [proc]
                                                                                 (contains? proc :exit))}
                                                            ls-cmd "foobar")
                                                  :exit)}}
        (is (pos? (bb "run" "--prn" "foo")))))
    (testing "shell test with :error"
      (test-utils/with-config
        {:tasks {'foo (list '-> (list 'shell {:out      out
                                              :err      out
                                              :error-fn '(constantly 1337)}
                                      ls-cmd "foobar"))}}
        (is (= 1337 (bb "run" "--prn" "foo"))))
      (test-utils/with-config
        {:tasks {'foo (list '-> (list 'shell {:out out
                                              :err out
                                              :error-fn
                                              '(fn [opts]
                                                 (and (:task opts)
                                                      (:proc opts)
                                                      (not (zero? (:exit (:proc opts))))))}
                                      ls-cmd "foobar"))}}
        (is (true? (bb "run" "--prn" "foo")))))
    (fs/delete out)
    (testing "clojure test"
      (test-utils/with-config {:tasks {'foo (list 'clojure {:out out}
                                                  "-M -e" "(println :yolo)")}}
        (bb "foo")
        (is (= ":yolo\n" (fix-lines (slurp out))))))
    (fs/delete out)
    (testing "depends"
      (test-utils/with-config {:tasks {'quux (list 'spit out "quux\n")
                                       'baz  (list 'spit out "baz\n" :append true)
                                       'bar  {:depends ['baz]
                                              :task    (list 'spit out "bar\n" :append true)}
                                       'foo  {:depends ['quux 'bar 'baz]
                                              :task    (list 'spit out "foo\n" :append true)}}}
        (bb "foo")
        (is (= "quux\nbaz\nbar\nfoo\n" (slurp out)))))
    (fs/delete out)
    ;; This is why we don't support :when for now
    #_(testing "depends with :when"
        (test-utils/with-config {:tasks {'quux (list 'spit out "quux\n")
                                         'baz  (list 'spit out "baz\n" :append true)
                                         'bar  {:when    false
                                                :depends ['baz]
                                                :task    (list 'spit out "bar\n" :append true)}
                                         'foo  {:depends ['quux 'bar]
                                                :task    (list 'spit out "foo\n" :append true)}}}
          (bb "foo")
          (is (= "quux\nbaz\nbar\nfoo\n" (slurp out))))))
  (testing "fully qualified symbol execution"
    (test-utils/with-config {:paths ["test-resources/task_scripts"]
                             :tasks '{foo tasks/foo}}
      (is (= :foo (bb "run" "--prn" "foo"))))
    (test-utils/with-config {:paths ["test-resources/task_scripts"]
                             :tasks '{:requires ([tasks :as t])
                                      foo       t/foo}}
      (is (= :foo (bb "run" "--prn" "foo"))))
    (test-utils/with-config {:paths ["test-resources/task_scripts"]
                             :tasks '{foo {:requires ([tasks :as t])
                                           :task     t/foo}}}
      (is (= :foo (bb "run" "--prn" "foo")))))
  (testing "extra-paths"
    (test-utils/with-config {:paths ["test-resources/task_scripts"]
                             :tasks '{:requires ([tasks :as t])
                                      foo       {:extra-paths ["test-resources/task_test_scripts"]
                                                 :requires    ([task-test :as tt])
                                                 :task        tt/task-test-fn}}}
      (is (= :task-test-fn (bb "run" "--prn" "foo")))))
  (testing "extra-deps"
    (test-utils/with-config {:tasks '{foo {:extra-deps {medley/medley {:mvn/version "1.3.0"}}
                                           :requires   ([medley.core :as m])
                                           :task       (m/index-by :id [{:id 1} {:id 2}])}}}
      (is (= {1 {:id 1}, 2 {:id 2}} (bb "run" "--prn" "foo")))))
  (testing "enter / leave"
    (test-utils/with-config '{:tasks {:init  (do (def enter-ctx (atom []))
                                                 (def leave-ctx (atom [])))
                                      :enter (swap! enter-ctx conj (:name (current-task)))
                                      :leave (swap! leave-ctx conj (:name (current-task)))
                                      foo    {:depends [bar]
                                              :task    [@enter-ctx @leave-ctx]}
                                      bar    {:depends [baz]}
                                      baz    {:enter nil
                                              :leave nil}}}
      (is (= '[[bar foo] [bar]] (bb "run" "--prn" "foo")))))
  (testing "run"
    (test-utils/with-config '{:tasks {a (+ 1 2 3)
                                      b (prn (run 'a))}}
      (is (= 6 (bb "run" "b")))))
  (testing "no such task"
    (test-utils/with-config '{:tasks {a (+ 1 2 3)}}
      (is (thrown-with-msg?
           Exception #"No such task: b"
           (bb "run" "b")))))
  (testing "unresolved dependency"
    (test-utils/with-config '{:tasks {a (+ 1 2 3)
                                      b {:depends [x]
                                         :task    (+ a 4 5 6)}}}
      (is (thrown-with-msg?
           Exception #"No such task: x"
           (bb "run" "b")))))
  (testing "cyclic task"
    (test-utils/with-config '{:tasks {b {:depends [b]
                                         :task    (+ a 4 5 6)}}}
      (is (thrown-with-msg?
           Exception #"Cyclic task: b"
           (bb "run" "b"))))
    (test-utils/with-config '{:tasks {c {:depends [b]}
                                      b {:depends [c]
                                         :task    (+ a 4 5 6)}}}
      (is (thrown-with-msg?
           Exception #"Cyclic task: b"
           (bb "run" "b")))))
  (testing "doc"
    (test-utils/with-config '{:tasks {b {:doc "Beautiful docstring"}}}
      (let [s (test-utils/bb nil "doc" "b")]
        (is (= "-------------------------\nb\nTask\nBeautiful docstring\n" s)))))
  (testing "system property"
    (test-utils/with-config '{:tasks {b (System/getProperty "babashka.task")}}
      (let [s (bb "run" "--prn" "b")]
        (is (= "b" s)))))
  (testing "parallel test"
    (test-utils/with-config (edn/read-string (slurp "test-resources/coffee-tasks.edn"))
      (let [tree             [:made-coffee [[:ground-beans [:measured-beans]] [:heated-water [:poured-water]] :filter :mug]]
            t0               (System/currentTimeMillis)
            s                (bb "run" "--prn" "coffeep")
            t1               (System/currentTimeMillis)
            delta-sequential (- t1 t0)]
        (is (= tree s))
        (test-utils/with-config (edn/read-string (slurp "test-resources/coffee-tasks.edn"))
          (let [t0             (System/currentTimeMillis)
                s              (bb "run" "--parallel" "--prn" "coffeep")
                t1             (System/currentTimeMillis)
                delta-parallel (- t1 t0)]
            (is (= tree s))
            (when (>=  (doto (-> (Runtime/getRuntime) (.availableProcessors))
                         (prn))
                       2)
              (is (< delta-parallel delta-sequential)))))))
    (testing "exception"
      (test-utils/with-config '{:tasks {a (Thread/sleep 10000)
                                        b (do (Thread/sleep 10)
                                              (throw (ex-info "0 noes" {})))
                                        c {:depends [a b]}}}
        (is (thrown-with-msg? Exception #"0 noes"
                              (bb "run" "--parallel" "c")))))
    (testing "edge case"
      (test-utils/with-config '{:tasks
                                {a   (run '-a {:parallel true})
                                 -a  {:depends [a:a a:b c]
                                      :task    (prn [a:a a:b c])}
                                 a:a {:depends [c]
                                      :task    (+ 1 2 3)}
                                 a:b {:depends [c]
                                      :task    (do (Thread/sleep 10)
                                                   (+ 1 2 3))}
                                 c   (do (Thread/sleep 10) :c)}}
        (is (= [6 6 :c] (bb "run" "--prn" "a"))))))
  (testing "dynamic vars"
    (test-utils/with-config '{:tasks
                              {:init (def ^:dynamic *foo* true)
                               a     (do
                                       (def ^:dynamic *bar* false)
                                       (binding [*foo* false
                                                 *bar* true]
                                         [*foo* *bar*]))}}
      (is (= [false true] (bb "run" "--prn" "a")))))
  (testing "stable namespace name"
    (test-utils/with-config '{:tasks
                              {:init   (do (def ^:dynamic *jdk*)
                                           (def ^:dynamic *server*))
                               server  [*jdk* *server*]
                               run-all (for [jdk    [8 11 15]
                                             server [:foo :bar]]
                                         (binding [*jdk*    jdk
                                                   *server* server]
                                           (babashka.tasks/run 'server)))}}
      (is (= '([8 :foo] [8 :bar] [11 :foo] [11 :bar] [15 :foo] [15 :bar])
             (bb "run" "--prn" "run-all")))))
  ;; TODO: disabled because of " Volume in drive C has no label.\r\n Volume Serial Number is 1CB8-D4AA\r\n\r\n Directory of C:\\projects\\babashka\r\n\r\n" on Appveyor. See https://ci.appveyor.com/project/borkdude/babashka/builds/40003094.
  (testing "shell test with :continue"
    (let [ls-cmd  (if main/windows? "cmd /c dir" "ls")]
      (test-utils/with-config {:tasks {'foo (list 'do
                                                  (list 'shell {:continue true}
                                                        (str ls-cmd " foobar"))
                                                  (list 'println :hello))}}
        (is (str/includes? (test-utils/bb nil "foo") ":hello"))))))

(deftest ^:skip-windows unix-task-test
  (testing "shell pipe test"
    (test-utils/with-config '{:tasks {a (-> (shell {:out :string}
                                                   "echo hello")
                                            (shell {:out :string} "cat")
                                            :out)}}
      (let [s (bb "run" "--prn" "a")]
        (is (= "hello\n" s))))))

(deftest ^:windows-only win-task-test
  (when main/windows?
    (testing "shell pipe test"
                                        ; this task prints the contents of deps.edn
      (test-utils/with-config '{:tasks {a (->> (shell {:out :string}
                                                      "cmd /c echo deps.edn")
                                               :out
                                               clojure.string/trim-newline
                                               (shell {:out :string} "cmd /c type")
                                               :out)}}
        (let [s (bb "run" "--prn" "a")]
          (is (str/includes? s "paths")))))))

(deftest list-tasks-test
  (test-utils/with-config {}
    (let [res (test-utils/bb nil "tasks")]
      (is (str/includes? res "No tasks found."))))
  (test-utils/with-config "{:paths [\"test-resources/task_scripts\"]
                            :tasks {:requires ([tasks :as t])
                                    task1
                                    {:doc \"task1 doc
more stuff here
even more stuff here\"
                                     :task (+ 1 2 3)}
                                    task2
                                    {:doc \"task2 doc\"
                                     :task (+ 4 5 6)}
                                    -task3
                                    {:task (+ 1 2 3)}
                                    task4
                                    {:task (+ 1 2 3)
                                     :private true}
                                    foo tasks/foo
                                    bar t/foo
                                    baz non-existing/bar
                                    quux {:requires ([tasks :as t2])
                                          :task t2/foo}}}"
    (let [res (test-utils/bb nil "tasks")]
      (is (= "The following tasks are available:\n\ntask1 task1 doc\ntask2 task2 doc\nfoo   Foo docstring\nbar   Foo docstring\nbaz  \nquux  Foo docstring\n"
             res))))
  (testing ":tasks is the first node"
    (test-utils/with-config "{:tasks {task1
                                    {:doc \"task1 doc\"
                                     :task (+ 1 2 3)}}}"
      (let [res (test-utils/bb nil "tasks")]
        (is (= "The following tasks are available:\n\ntask1 task1 doc\n"
               res))))))

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

(deftest min-bb-version-test
  (fs/with-temp-dir [dir {}]
    (let [config (str (fs/file dir "bb.edn"))]
      (spit config '{:min-bb-version "300.0.0"})
      (let [sw (java.io.StringWriter.)]
        (binding [*err* sw]
          (main/main "--config" config  "-e" "nil"))
        (is (str/includes? (str sw)
                           "WARNING: this project requires babashka 300.0.0 or newer, but you have: "))))))

(deftest classpath-other-bb-edn-test
  (fs/with-temp-dir [dir {}]
    (let [config (str (fs/file dir "bb.edn"))]
      (spit config '{:paths ["src"]
                     :tasks {cp (prn (babashka.classpath/get-classpath))}})
      (let [out (bb "--config" config "cp")
            entries (cp/split-classpath out)
            entry (first entries)]
        (is (= 1 (count entries)))
        (is (= (fs/parent config) (fs/parent entry)))
        (is (str/ends-with? entry "src"))))))

(deftest without-deps-test
  (when-not test-utils/native?
    (with-redefs [borkdude.deps/-main (fn [& _]
                                        (throw (ex-info "This ain't allowed!" {})))]
      (testing "bb.edn without :deps should not require deps.clj"
        (test-utils/with-config '{:tasks {a 1}}
          (bb "-e" "(+ 1 2 3)"))))))

(deftest deps-race-condition-test
  (test-utils/with-config
    (pr-str '{:tasks {task-b (do
                               (Thread/sleep 10)
                               :task00-out)
                      task-c {:depends [task-b]
                              :task (do
                                      (println
                                       "task-b: "
                                       (type task-b))
                                      {})}
                      task-a {:task (do
                                      (Thread/sleep 10)
                                      :task0-out)}
                      task-e {:depends [task-e1] :task {}}
                      task-e2 {:depends [task-a] :task {}}
                      task-e3 {:depends [task-b] :task {}}
                      task-e1 {:depends [task-e2 task-e3]
                               :task {}}
                      task-h {:depends [task-a task-b]
                              :task {}}
                      task-d {:task (do (Thread/sleep 2) {})}
                      task-f {:depends [task-d task-e task-a]
                              :task {}}
                      task-g {:depends [task-f
                                        task-d
                                        task-a
                                        task-c
                                        task-h]
                              :task {}}}})
    (time (dotimes [_ 50]
            (is (str/includes? (test-utils/bb nil "run" "--parallel" "task-g")
                               "task-b:  clojure.lang.Keyword"))))))

(deftest parallel-nil-results-test
  (test-utils/with-config
    (pr-str '{:tasks {a (do nil)
                      b (do nil)
                      c (do nil)
                      d {:depends [a b c]
                         :task (prn [a b c])}}})
    (is (= [nil nil nil] (bb "run" "--parallel" "d")))))

(deftest current-task-result-test
  (test-utils/with-config
    (pr-str '{:tasks {:leave (prn [(:name (current-task)) (:result (current-task))])
                      a 1
                      b 2
                      c {:depends [a b]
                         :task [a b]}}})
    (is (= ["[a 1]" "[b 2]" "[c [1 2]]"] (str/split-lines (test-utils/bb nil "run" "c"))))))

(deftest pod-from-registry-test
  (when (= "amd64" (System/getProperty "os.arch")) ; TODO: Build bootleg for aarch64 too or use a different pod
    (test-utils/with-config
      (pr-str '{:paths ["test-resources"]
                :pods  {retrogradeorbit/bootleg {:version "0.1.9"}}})
      (is (= "\"<div><p>Test</p></div>\"\n"
             (test-utils/bb nil "-m" "pod-tests.bootleg"))))))

(deftest ^:skip-windows local-pod-test
  (test-utils/with-config
    (pr-str '{:paths ["test-resources"]
              :pods {pod/test-pod {:path "test-resources/pod"}}})
    (is (= "42\n" (test-utils/bb nil "-m" "pod-tests.local")))))
