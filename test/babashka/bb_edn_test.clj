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
                                      foo x}}
      (is (= 1 (bb "run" "--prn" "foo")))))
  (testing "requires test"
    (test-utils/with-config '{:tasks {:requires ([babashka.fs :as fs])
                                      foo (fs/exists? ".")}}
      (is (= true (bb "run" "--prn" "foo"))))
    (test-utils/with-config '{:tasks {foo {:requires ([babashka.fs :as fs])
                                           :task (fs/exists? ".")}}}
      (is (= true (bb "run" "--prn" "foo"))))
    (test-utils/with-config '{:tasks {bar {:requires ([babashka.fs :as fs])}
                                      foo {:depends [bar]
                                           :task (fs/exists? ".")}}}
      (is (= true (bb "run" "--prn" "foo")))))
  (testing "map returned from task"
    (test-utils/with-config '{:tasks {foo {:task {:a 1 :b 2}}}}
      (is (= {:a 1 :b 2} (bb "run" "--prn" "foo")))))
  (let [tmp-dir (fs/create-temp-dir)
        out (str (fs/file tmp-dir "out.txt"))
        echo-cmd (if main/windows? "cmd /c echo" "echo")
        ls-cmd (if main/windows? "cmd /c dir" "ls")
        fix-lines test-utils/normalize]
    (testing "shell test"
      (test-utils/with-config {:tasks {'foo (list 'shell {:out out}
                                                  echo-cmd "hello")}}
        (bb "foo")
        (is (= "hello\n" (fix-lines (slurp out))))))
    (fs/delete out)
    (testing "shell test with :continue fn"
      (test-utils/with-config {:tasks {'foo (list '-> (list 'shell {:out out
                                                                    :err out
                                                                    :continue '(fn [proc]
                                                                                 (contains? proc :exit))}
                                                            ls-cmd "foobar")
                                                  :exit)}}
        (is (pos? (bb "run" "--prn" "foo")))))
    (testing "shell test with :error"
      (test-utils/with-config
        {:tasks {'foo (list '-> (list 'shell {:out out
                                              :err out
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
                                       'baz (list 'spit out "baz\n" :append true)
                                       'bar {:depends ['baz]
                                             :task (list 'spit out "bar\n" :append true)}
                                       'foo {:depends ['quux 'bar 'baz]
                                             :task (list 'spit out "foo\n" :append true)}}}
        (bb "foo")
        (is (= "quux\nbaz\nbar\nfoo\n" (slurp out)))))
    (fs/delete out)
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
          (is (= "quux\nbaz\nbar\nfoo\n" (slurp out))))))
  (testing "fully qualified symbol execution"
    (test-utils/with-config {:paths ["test-resources/task_scripts"]
                             :tasks '{foo tasks/foo}}
      (is (= :foo (bb "run" "--prn" "foo"))))
    (test-utils/with-config {:paths ["test-resources/task_scripts"]
                             :tasks '{:requires ([tasks :as t])
                                      foo t/foo}}
      (is (= :foo (bb "run" "--prn" "foo"))))
    (test-utils/with-config {:paths ["test-resources/task_scripts"]
                             :tasks '{foo {:requires ([tasks :as t])
                                           :task t/foo}}}
      (is (= :foo (bb "run" "--prn" "foo")))))
  (testing "extra-paths"
    (test-utils/with-config {:paths ["test-resources/task_scripts"]
                             :tasks '{:requires ([tasks :as t])
                                      foo {:extra-paths ["test-resources/task_test_scripts"]
                                           :requires ([task-test :as tt])
                                           :task tt/task-test-fn}}}
      (is (= :task-test-fn (bb "run" "--prn" "foo")))))
  (testing "extra-deps"
    (test-utils/with-config {:tasks '{foo {:extra-deps {medley/medley {:mvn/version "1.3.0"}}
                                           :requires ([medley.core :as m])
                                           :task (m/index-by :id [{:id 1} {:id 2}])}}}
      (is (= {1 {:id 1}, 2 {:id 2}} (bb "run" "--prn" "foo")))))
  (testing "enter / leave"
    (test-utils/with-config '{:tasks {:init (do (def enter-ctx (atom []))
                                                (def leave-ctx (atom [])))
                                      :enter (swap! enter-ctx conj (:name (current-task)))
                                      :leave (swap! leave-ctx conj (:name (current-task)))
                                      foo {:depends [bar]
                                           :task [@enter-ctx @leave-ctx]}
                                      bar {:depends [baz]}
                                      baz {:enter nil
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
                                         :task (+ a 4 5 6)}}}
      (is (thrown-with-msg?
           Exception #"No such task: x"
           (bb "run" "b")))))
  (testing "cyclic task"
    (test-utils/with-config '{:tasks {b {:depends [b]
                                         :task (+ a 4 5 6)}}}
      (is (thrown-with-msg?
           Exception #"Cyclic task: b"
           (bb "run" "b"))))
    (test-utils/with-config '{:tasks {c {:depends [b]}
                                      b {:depends [c]
                                         :task (+ a 4 5 6)}}}
      (is (thrown-with-msg?
           Exception #"Cyclic task: b"
           (bb "run" "b")))))
  (testing "friendly regex literal error handling"
    (test-utils/with-config
      "{:tasks {something (clojure.string/split \"1-2\" #\"-\")}}"
      (is (thrown-with-msg?
           Exception #"Invalid regex literal"
           (bb "run" "something")))))
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
      (let [tree [:made-coffee [[:ground-beans [:measured-beans]] [:heated-water [:poured-water]] :filter :mug]]
            t0 (System/currentTimeMillis)
            s (bb "run" "--prn" "coffeep")
            t1 (System/currentTimeMillis)
            delta-sequential (- t1 t0)]
        (is (= tree s))
        (test-utils/with-config (edn/read-string (slurp "test-resources/coffee-tasks.edn"))
          (let [t0 (System/currentTimeMillis)
                s (bb "run" "--parallel" "--prn" "coffeep")
                t1 (System/currentTimeMillis)
                delta-parallel (- t1 t0)]
            (is (= tree s))
            (when (>= (doto (-> (Runtime/getRuntime) (.availableProcessors))
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
                                {a (run '-a {:parallel true})
                                 -a {:depends [a:a a:b c]
                                     :task (prn [a:a a:b c])}
                                 a:a {:depends [c]
                                      :task (+ 1 2 3)}
                                 a:b {:depends [c]
                                      :task (do (Thread/sleep 10)
                                                (+ 1 2 3))}
                                 c (do (Thread/sleep 10) :c)}}
        (is (= [6 6 :c] (bb "run" "--prn" "a"))))))
  (testing "dynamic vars"
    (test-utils/with-config '{:tasks
                              {:init (def ^:dynamic *foo* true)
                               a (do
                                   (def ^:dynamic *bar* false)
                                   (binding [*foo* false
                                             *bar* true]
                                     [*foo* *bar*]))}}
      (is (= [false true] (bb "run" "--prn" "a")))))
  (testing "stable namespace name"
    (test-utils/with-config '{:tasks
                              {:init (do (def ^:dynamic *jdk*)
                                         (def ^:dynamic *server*))
                               server [*jdk* *server*]
                               run-all (for [jdk [8 11 15]
                                             server [:foo :bar]]
                                         (binding [*jdk* jdk
                                                   *server* server]
                                           (babashka.tasks/run 'server)))}}
      (is (= '([8 :foo] [8 :bar] [11 :foo] [11 :bar] [15 :foo] [15 :bar])
             (bb "run" "--prn" "run-all")))))
  ;; TODO: disabled because of " Volume in drive C has no label.\r\n Volume Serial Number is 1CB8-D4AA\r\n\r\n Directory of C:\\projects\\babashka\r\n\r\n" on Appveyor. See https://ci.appveyor.com/project/borkdude/babashka/builds/40003094.
  (testing "shell test with :continue"
    (let [ls-cmd (if main/windows? "cmd /c dir" "ls")]
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

(deftest tasks:clojure-test
  (testing "tokenization when called from tasks"
    (test-utils/with-config
      (pr-str '{:tasks {foo (-> (clojure {:out :string} "-J-Dfoo=\"{:port 5555 :accept clojure.core.server/repl}\" -M -e \"(clojure.edn/read-string (System/getProperty (name :foo)))\"") :out clojure.edn/read-string prn)}})
      (is (= '{:port 5555, :accept clojure.core.server/repl}
             (bb "run" "foo")))))
  (testing "can be called without args"
    (test-utils/with-config
      (pr-str '{:tasks {foo (-> (clojure {:in "(+ 1 2 3)" :out :string}) :out prn)}})
      (is (str/includes? (bb "run" "foo") "6")))
    ;; can't properly test this, but `(clojure)` should work with zero args
    #_(test-utils/with-config
        (pr-str '{:tasks {foo (-> (clojure) :out prn)}})
        (is (str/includes? (test-utils/bb "(+ 1 2 3)" "run" "foo") "6"))))
  (testing "call to run in missing dir gives 'cannot run program' message"
    (test-utils/with-config
      (pr-str '{:tasks {foo (clojure {:dir "../missingdir"} "-M" "-r")}})
      ;; check rough text of error message, specific message about missing directory is OS-dependent
      (is (thrown-with-msg? Exception #"Cannot run program .* \(in directory \"\.\.[/\\]missingdir\"\)"
                            (bb "run" "foo"))))))

(deftest list-tasks-test
  (test-utils/with-config {}
    (let [res (test-utils/bb nil "tasks")]
      (is (str/includes? res "No tasks found."))))
  (test-utils/with-config '{:tasks {:x 1}}
    (let [res (test-utils/bb nil "tasks")]
      (is (str/includes? res "No tasks found."))))
  (test-utils/with-config '{:tasks {-xyz 5}}
    (let [res (test-utils/bb nil "tasks")]
      (is (str/includes? res "No tasks found."))))
  (test-utils/with-config '{:tasks {xyz {:private true}}}
    (let [res (test-utils/bb nil "tasks")]
      (is (str/includes? res "No tasks found."))))
  (test-utils/with-config '{:tasks {abc 1 xyz 2}}
    (let [res (test-utils/bb nil "tasks")]
      (is (= "The following tasks are available:\n\nabc\nxyz\n" res))))
  (test-utils/with-config '{:tasks {abc 1 xyz {:doc "some text" :tasks 5}
                                    -xyz 3 qrs {:private true}}}
    (let [res (test-utils/bb nil "tasks")]
      (is (= "The following tasks are available:\n\nabc\nxyz  some text\n" res))))
  (test-utils/with-config '{:tasks {xyz 1 abc 2}}
    (let [res (test-utils/bb nil "tasks")]
      (is (= "The following tasks are available:\n\nxyz\nabc\n" res))))
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
      (is (= "The following tasks are available:\n\ntask1  task1 doc\ntask2  task2 doc\nfoo    Foo docstring\nbar    Foo docstring\nbaz  \nquux   Foo docstring\n"
             res))))
  (testing ":tasks is the first node"
    (test-utils/with-config "{:tasks {task1
                                    {:doc \"task1 doc\"
                                     :task (+ 1 2 3)}}}"
      (let [res (test-utils/bb nil "tasks")]
        (is (= "The following tasks are available:\n\ntask1  task1 doc\n"
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
        (is (= {:file "uberjar", :command-line-args '("--version")} (second (main/parse-opts ["uberjar" "--version"]))))
        (finally (fs/delete "uberjar"))))))

(deftest min-bb-version-test
  (fs/with-temp-dir [dir {}]
    (let [config (str (fs/file dir "bb.edn"))]
      (spit config '{:min-bb-version "300.0.0"})
      (let [sw (java.io.StringWriter.)]
        (binding [*err* sw]
          (main/main "--config" config "-e" "nil"))
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
        (is (= (fs/real-path (fs/parent config)) (fs/real-path (fs/parent entry))))
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
                :pods {retrogradeorbit/bootleg {:version "0.1.9"}}})
      (is (= "\"<div><p>Test</p></div>\"\n"
             (test-utils/bb nil "--prn" "-m" "pod-tests.bootleg"))))))

(deftest ^:skip-windows local-pod-test
  (test-utils/with-config
    (pr-str '{:paths ["test-resources"]
              :pods {pod/test-pod {:path "test-resources/pod"}}})
    (is (= "42\n" (test-utils/bb nil "-m" "pod-tests.local")))))

(deftest tag-test
  (test-utils/with-config
    "{:deps {}
      :aliases {:foo {:env-vars {:dude #env \"DUDE\"}}}}"
    (is (= 6 (bb "-e" "(+ 1 2 3)")))))

(deftest merge-deps-test
  (test-utils/with-config
    "{:deps {}}"
    (is (= {1 {:a 1}}
           (bb "-Sdeps" "{:deps {medley/medley {:mvn/version \"1.4.0\"}}}" "-e" "(require 'medley.core) (medley.core/index-by :a [{:a 1}])")))))

(deftest deps-root-test
  (fs/with-temp-dir [dir {}]
    (let [f (fs/file dir "bb.edn")
          config (str f)]
      (spit config
            '{:paths ["src"]
              :tasks {cp (prn (babashka.classpath/get-classpath))}})
      (testing "custom deps-root path"
        (let [out (bb "--config" config "--deps-root" (str dir) "cp")
              entries (cp/split-classpath out)]
          (is (= 1 (count entries)))
          (is (= (fs/file dir "src") (fs/file (first entries))))))
      (testing "default deps-root path is same as bb.edn"
        (let [out (bb "--config" config "cp")
              entries (cp/split-classpath out)]
          (is (= (fs/real-path(fs/parent f)) (fs/real-path (fs/parent (first entries)))))))
      (spit config
            '{:paths ["src"]
              :deps {local/dep {:local/root "local-dep"}}
              :tasks {cp (prn (babashka.classpath/get-classpath))}})
      (testing "relative paths in deps should be relative to bb.edn"
        (let [root (fs/create-dir (fs/file dir "local-dep"))
              _ (spit (str (fs/file root "deps.edn")) {})
              out (bb "--config" config "cp")
              entries (cp/split-classpath out)]
          (is (= (fs/real-path (fs/parent f)) (fs/real-path (fs/parent (first entries))))))))))

(deftest adjacent-bb-edn-test
  (is (= {1 {:id 1}} (bb "test-resources/adjacent_bb/medley.bb")))
  (is (= {1 {:id 1}} (bb "-f" "test-resources/adjacent_bb/medley.bb")))
  (testing "symlink"
    (is (= {1 {:id 1}} (bb (str (fs/file "test-resources" "symlink-adjacent-bb")))))))

; symlinks that resolve in the /proc fs cause fs/real-path to throw when figuring out bb.edn path (issue #1700)
(deftest redirection-test
  (testing "main doesn't throw when input file symlink resolves to 'not real' file"
    (when (and test-utils/native? (not test-utils/windows?))
      (is (str/starts-with? (test-utils/bb "(println \"hi\")" "/dev/stdin") "hi")))))

(deftest non-existing-tasks-in-run-gives-exit-code-1
  (is (thrown? Exception (bb "-Sdeps" "{:tasks {foo {:task (run (quote bar))}}}" "foo"))))

(deftest empty-bb-edn-test
  (is (= 6 (bb "-Sdeps" "" "-e" "(+ 1 2 3)"))))

(deftest multiple-forms-bb-edn-test
  (is (thrown-with-msg? Exception #"should contain zero or one form"
                        (bb "-Sdeps" "{:deps {}} {:paths []}" "-e" "(+ 1 2 3)"))))

(deftest warning-on-override-task
  (when-not test-utils/native?
    (binding [*out* *err*]
      (is (str/includes? (with-out-str (bb "-Sdeps" "{:tasks {run {:task 1}}}" "run")) "'run' override")))))

(deftest init-is-ran-before-task-specific-requires-but-after-global-requires-and-init-is-ran-only-once-test
  (fs/with-temp-dir [dir {}]
    (let [f (fs/file dir "bb.edn")
          pre-init-file (fs/file dir "pre_init.clj")
          after-init-file (fs/file dir "after_init.clj")
          config (str f)]
      (spit config
            '{:tasks {:requires ([pre-init] [babashka.fs :as fs])
                      :init (prn :init (fs/exists? "."))
                      task-b {:requires ([after-init])}
                      task-a {:extra-paths ["."]
                              :requires ([after-init])
                              :task
                              ;; this caused init to be re-ran
                              (run 'task-b)}}})
      (spit pre-init-file "(ns pre-init) (prn :pre-init)")
      (spit after-init-file "(ns after-init) (prn :after-init)")
      (let [out (str/split-lines (test-utils/bb nil "--config" config "task-a"))]
        (is (= [":pre-init" ":init true" ":after-init"] out))))))

(deftest task-cli-test
  (testing "--help on a CLI task prints help and exits 0"
    (test-utils/with-config '{:tasks {foo {:cli {:spec {:port {:desc "Port"}}}
                                           :exec-fn clojure.core/prn}}}
      (let [out (test-utils/bb nil "foo" "--help")]
        (is (str/includes? out "Usage: bb foo"))
        (is (str/includes? out "--port")))))
  (testing "tasks without :cli pass --help through to the body"
    (test-utils/with-config '{:tasks {foo (prn *command-line-args*)}}
      (is (= ["--help"] (bb "foo" "--help")))))
  (testing ":cmd tree dispatches subcommands, nested too"
    (test-utils/with-config '{:tasks {deps {:cmd {"outdated" {:fn babashka.tasks-cli/outdated
                                                              :spec {:format {}}}
                                                  "cache" {:cmd {"clean" {:fn babashka.tasks-cli/clean}}}}}}}
      (is (= {:format "edn" :ran :outdated}
             (bb "-cp" "test-resources" "deps" "outdated" "--format" "edn")))
      (is (= {:ran :clean}
             (bb "-cp" "test-resources" "deps" "cache" "clean")))))
  (testing "a CLI task named in :depends runs, with the keys it declared"
    (test-utils/with-config '{:tasks {-build {:exec-fn babashka.tasks-cli/dep-build}
                                      tst {:depends [-build]
                                           :exec-fn babashka.tasks-cli/dep-test}}}
      (let [lines (fn [& args]
                    (map edn/read-string
                         (str/split-lines (apply test-utils/bb nil "-cp" "test-resources" args))))]
        (testing "the dep's handler runs before the target's"
          (is (= [{:ran :dep-build} {:watch true :ran :dep-test}]
                 (lines "tst" "--watch"))))
        (testing "the dep's spec merges into the parse, so :restrict accepts it"
          (is (= [{:target "x" :ran :dep-build} {:target "x" :ran :dep-test}]
                 (lines "tst" "--target" "x"))))
        (testing "--help lists the dep's options alongside the task's own"
          (let [help (test-utils/bb nil "-cp" "test-resources" "tst" "--help")]
            (is (str/includes? help "--watch"))
            (is (str/includes? help "--target")))))))
  (testing ":depends cannot name a :cmd task, which has no single handler"
    (test-utils/with-config '{:tasks {-tree {:cmd {"sub" {:exec-fn babashka.tasks-cli/dep-build}}}
                                      tst {:depends [-tree]
                                           :exec-fn babashka.tasks-cli/dep-test}}}
      (is (thrown-with-msg?
           Exception #":depends cannot name -tree"
           (bb "-cp" "test-resources" "tst")))))
  (testing "a :cmd task may name a CLI task in :depends"
    (test-utils/with-config '{:tasks {-build {:exec-fn babashka.tasks-cli/dep-build}
                                      deploy {:depends [-build]
                                              :cmd {"lock" {:exec-fn babashka.tasks-cli/dep-test}}}}}
      (is (= [{:target "x" :ran :dep-build} {:target "x" :ran :dep-test}]
             (map edn/read-string
                  (str/split-lines
                   (test-utils/bb nil "-cp" "test-resources" "deploy" "lock" "--target" "x")))))
      (testing "the dep's options show in the leaf's help"
        (is (str/includes? (test-utils/bb nil "-cp" "test-resources" "deploy" "lock" "--help")
                           "--target")))))
  ;; a parallel dep runs on its own thread, where the in-process harness does not
  ;; capture *out*, so this reports through a file
  (testing "under --parallel a CLI dep's handler runs, before the target"
    (let [out (str (fs/file (fs/create-temp-dir) "out.txt"))]
      (test-utils/with-config '{:tasks {-build {:exec-fn babashka.tasks-cli/dep-spit}
                                        tst {:depends [-build]
                                             :exec-fn babashka.tasks-cli/target-spit}}}
        (test-utils/bb nil "-cp" "test-resources" "run" "--parallel" "tst" "--out" out)
        (is (= "dep\ntarget\n" (slurp out))))))
  (testing "a dep's spec may live under its :cli, not only on its handler"
    (test-utils/with-config '{:tasks {-build {:exec-fn babashka.tasks-cli/dep-build
                                              :cli {:spec {:under-cli {}}}}
                                      tst {:depends [-build]
                                           :exec-fn babashka.tasks-cli/dep-test}}}
      (is (= [{:under-cli "y" :ran :dep-build} {:under-cli "y" :ran :dep-test}]
             (map edn/read-string
                  (str/split-lines
                   (test-utils/bb nil "-cp" "test-resources" "tst" "--under-cli" "y")))))
      (is (str/includes? (test-utils/bb nil "-cp" "test-resources" "tst" "--help")
                         "--under-cli"))))
  (testing "a CLI dep keeps its place in the graph, among plain deps"
    (doseq [args [["tst"] ["run" "--parallel" "tst"]]]
      (let [out (str (fs/file (fs/create-temp-dir) "order.txt"))]
        (test-utils/with-config '{:tasks {-a {:exec-fn babashka.tasks-cli/mark-a}
                                          -b {:depends [-a]
                                              :task (spit (last *command-line-args*) "b\n" :append true)}
                                          tst {:depends [-b]
                                               :exec-fn babashka.tasks-cli/target-spit}}}
          (apply test-utils/bb nil "-cp" "test-resources" (concat args ["--out" out]))
          (is (= "a\nb\ntarget\n" (slurp out)) (str "for " args))))))
  (testing "a CLI dep waits for the CLI dep it depends on, under --parallel"
    (let [out (str (fs/file (fs/create-temp-dir) "chain.txt"))]
      (test-utils/with-config '{:tasks {-a {:exec-fn babashka.tasks-cli/mark-a}
                                        -c {:depends [-a] :exec-fn babashka.tasks-cli/mark-c}
                                        tst {:depends [-c]
                                             :exec-fn babashka.tasks-cli/target-spit}}}
        (test-utils/bb nil "-cp" "test-resources" "run" "--parallel" "tst" "--out" out)
        (is (= "a\nc\ntarget\n" (slurp out))))))
  (testing "under --parallel CLI deps run at the same time, not one after the other"
    (let [dir (str (fs/create-temp-dir))]
      (test-utils/with-config '{:tasks {-a {:exec-fn babashka.tasks-cli/rendezvous-a}
                                        -b {:exec-fn babashka.tasks-cli/rendezvous-b}
                                        tst {:depends [-a -b]
                                             :exec-fn babashka.tasks-cli/target-spit}}}
        (test-utils/bb nil "-cp" "test-resources" "run" "--parallel" "tst"
                       "--dir" dir "--out" (str dir "/out.txt"))
        (is (= "concurrent" (slurp (str dir "/a-result"))))
        (is (= "concurrent" (slurp (str dir "/b-result")))))))
  (testing ":cli :cmd subcommand fn pulls spec/args->opts from its :org.babashka/cli meta"
    (test-utils/with-config '{:tasks {deploy {:cmd {"lock" {:fn babashka.tasks-cli/lock}}}}}
      (is (= {:environment "staging" :message "msg" :ran :lock}
             (bb "-cp" "test-resources" "deploy" "lock" "staging" "-m" "msg")))
      (let [help (test-utils/bb nil "-cp" "test-resources" "deploy" "lock" "--help")]
        (is (str/includes? help "Usage: bb deploy lock"))
        (is (str/includes? help "--message"))
        (testing "the fn's docstring shows as the command doc"
          (is (str/includes? help "Lock deployment"))))))
  (testing "ns-level :org.babashka/cli metadata merges under fn metadata (like bb -x)"
    (test-utils/with-config '{:tasks {go {:exec-fn babashka.tasks-cli-ns/go}}}
      (testing "ns spec and fn spec both parse"
        (is (= {:port 1 :verbose true :ran :go}
               (bb "-cp" "test-resources" "go" "--port" "1" "--verbose"))))
      (testing "ns-level :restrict applies"
        (is (= {:exit 1 :cause :restrict}
               (bb "-cp" "test-resources" "-e"
                   "(require '[babashka.cli :as cli])
                    (binding [cli/*exit-fn* (fn [m] (prn (select-keys m [:exit :cause])))
                              *command-line-args* [\"--nope\"]]
                      (babashka.tasks/run (quote go)))"))))))
  (testing "a vector of [name command] pairs keeps the order it was written in"
    ;; more than 8 commands: an edn map would already have lost its order
    (test-utils/with-config '{:tasks {big {:cmd [["kilo" {:fn clojure.core/prn}]
                                                 ["juliet" {:fn clojure.core/prn}]
                                                 ["india" {:fn clojure.core/prn}]
                                                 ["hotel" {:fn clojure.core/prn}]
                                                 ["golf" {:fn clojure.core/prn}]
                                                 ["foxtrot" {:fn clojure.core/prn}]
                                                 ["echo" {:fn clojure.core/prn}]
                                                 ["delta" {:fn clojure.core/prn}]
                                                 ["charlie" {:fn clojure.core/prn}]
                                                 ["bravo" {:fn clojure.core/prn}]
                                                 ["alpha" {:fn clojure.core/prn}]]}}}
      (let [help (test-utils/bb nil "big" "--help")
            names ["kilo" "juliet" "india" "hotel" "golf" "foxtrot" "echo" "delta" "charlie" "bravo" "alpha"]]
        (is (apply < (map #(str/index-of help %) names))))
      (testing "and dispatches through it"
        (is (= {:dispatch ["alpha"] :opts {} :args ["1"]}
               (bb "big" "alpha" "1"))))))
  (testing "bb tasks derives a doc from a fn on the task's :extra-paths"
    (test-utils/with-config '{:tasks {foo {:extra-paths ["test-resources"]
                                           :exec-fn babashka.tasks-cli/deploy-x}}}
      (is (str/includes? (test-utils/bb nil "tasks") "Deploy it"))))
  (testing "a task :cli may name a var, like the runner-level one"
    (test-utils/with-config '{:tasks {foo {:cli babashka.tasks-cli/base-opts
                                           :exec-fn babashka.tasks-cli/deploy-x}}}
      (testing "so its options may hold functions, which bb.edn cannot"
        (is (thrown-with-msg?
             ;; base-opts' :error-fn, resolved from the task's own :cli
             Exception #"DEFAULTS-ERR :require \| Required option: --env"
             (test-utils/bb nil "-cp" "test-resources" "foo"))))
      (testing "and its spec still parses"
        (is (= {:env "prod" :ran :exec-only}
               (bb "-cp" "test-resources" "foo" "prod")))))
    (testing "a task :cli reaches dispatch, like the runner-level one"
      (test-utils/with-config '{:tasks {deploy {:cli babashka.tasks-cli/base-opts
                                                :cmd {"go" {:exec-fn babashka.tasks-cli/deploy-x}}}}}
        (testing "no command on a handler-less group goes through its :error-fn"
          (is (thrown-with-msg?
               Exception #"DEFAULTS-ERR :input-exhausted \| No command given\."
               (test-utils/bb nil "-cp" "test-resources" "deploy"))))
        (testing "an error inside a subcommand goes through it too"
          (is (thrown-with-msg?
               Exception #"DEFAULTS-ERR :require \| Required option: --env"
               (test-utils/bb nil "-cp" "test-resources" "deploy" "go"))))
        (testing "the happy path is unaffected"
          (is (= {:env "prod" :ran :exec-only}
                 (bb "-cp" "test-resources" "deploy" "go" "prod"))))))
    (testing "and anything else is a config error"
      (test-utils/with-config '{:tasks {foo {:cli 42 :exec-fn babashka.tasks-cli/deploy-x}}}
        (is (thrown-with-msg?
             Exception #"Task foo: :cli must be a map or a symbol naming a def"
             (test-utils/bb nil "-cp" "test-resources" "foo"))))))
  (testing "a handler that cannot be resolved names the task and the key"
    (testing "when the var is missing"
      (test-utils/with-config '{:tasks {foo {:exec-fn clojure.core/nope}}}
        (is (thrown-with-msg?
             Exception #"Task foo: cannot resolve :exec-fn clojure.core/nope"
             (test-utils/bb nil "-cp" "test-resources" "foo")))))
    (testing "and when the whole namespace is missing"
      (test-utils/with-config '{:tasks {foo {:exec-fn no.such.ns/handler}}}
        (is (thrown-with-msg?
             Exception #"Task foo: cannot resolve :exec-fn no.such.ns/handler: Could not locate"
             (test-utils/bb nil "-cp" "test-resources" "foo"))))))
  (testing "a runner-level :cli symbol that cannot be resolved is a config error"
    (testing "when the var is missing"
      (test-utils/with-config '{:tasks {:cli babashka.tasks-cli/nope
                                        foo {:exec-fn babashka.tasks-cli/deploy-x}}}
        (is (thrown-with-msg?
             Exception #":tasks :cli babashka.tasks-cli/nope cannot be resolved"
             (test-utils/bb nil "-cp" "test-resources" "foo" "prod")))))
    (testing "and when the whole namespace is missing"
      (test-utils/with-config '{:tasks {:cli no.such.ns/base
                                        foo {:exec-fn babashka.tasks-cli/deploy-x}}}
        (is (thrown-with-msg?
             Exception #":tasks :cli no.such.ns/base cannot be resolved: Could not locate"
             (test-utils/bb nil "-cp" "test-resources" "foo" "prod"))))))
  (testing "a :task that is a qualified symbol keeps its :cli dispatch"
    (test-utils/with-config '{:tasks {deploy {:task clojure.core/prn
                                              :cmd {"go" {:exec-fn babashka.tasks-cli/deploy-x}}}}}
      (is (str/includes? (test-utils/bb nil "-cp" "test-resources" "deploy" "--help")
                         "Usage: bb deploy"))
      (is (= {:env "prod" :ran :exec-only}
             (bb "-cp" "test-resources" "deploy" "go" "prod")))))
  (testing ":enter and :leave run for a task with a handler instead of a body"
    (test-utils/with-config '{:tasks {:enter (println "ENTER" (:name (current-task)))
                                      :leave (println "LEAVE" (:name (current-task)))
                                      foo {:exec-fn babashka.tasks-cli/deploy-x}
                                      grp {:cmd {"go" {:exec-fn babashka.tasks-cli/deploy-x}}}}}
      (let [out (test-utils/bb nil "-cp" "test-resources" "foo" "prod")]
        (is (str/includes? out "ENTER foo"))
        (is (str/includes? out "LEAVE foo")))
      (testing "for a command group leaf too"
        (let [out (test-utils/bb nil "-cp" "test-resources" "grp" "go" "prod")]
          (is (str/includes? out "ENTER grp"))
          (is (str/includes? out "LEAVE grp"))))
      (testing "but not when dispatch never reaches a handler"
        (let [out (test-utils/bb nil "-cp" "test-resources" "foo" "--help")]
          (is (str/includes? out "Usage: bb foo"))
          (is (not (str/includes? out "ENTER")))))))
  (testing "--help does not run a dependency's body"
    (test-utils/with-config '{:tasks {-dep {:requires ([babashka.tasks-cli-side])
                                            :task (println "DEP BODY")}
                                      foo {:depends [-dep]
                                           :exec-fn babashka.tasks-cli/deploy-x}}}
      (let [help (test-utils/bb nil "-cp" "test-resources" "foo" "--help")]
        (is (str/includes? help "Usage: bb foo"))
        (is (not (str/includes? help "DEP BODY")))
        (testing "the dependency's :requires are still processed, see ADR 0001"
          (is (str/includes? help "SIDE EFFECT"))))
      (testing "a real run runs the dependency and the handler"
        (let [out (test-utils/bb nil "-cp" "test-resources" "foo" "prod")]
          (is (str/includes? out "DEP BODY"))
          (is (str/includes? out ":ran :exec-only"))))))
  (testing "a dependency may use its own :requires alias in its body"
    (test-utils/with-config '{:tasks {-dep {:requires ([babashka.tasks-cli :as t])
                                            :task (t/clean {:opts {}})}
                                      foo {:depends [-dep]
                                           :exec-fn babashka.tasks-cli/deploy-x}}}
      (let [out (test-utils/bb nil "-cp" "test-resources" "foo" "prod")]
        (is (str/includes? out ":ran :clean")))
))
  (testing "a :cli entry in the :tasks map provides dispatch defaults"
    (test-utils/with-config '{:tasks {:cli {:restrict true :restrict-args true}
                                      foo {:exec-fn babashka.tasks-cli/run-dev}
                                      dep {:exec-fn babashka.tasks-cli/deploy-x}}}
      (testing "declared options still work"
        (is (= {:port 8080 :ran :run-dev}
               (bb "-cp" "test-resources" "foo" "--port" "8080"))))
      (testing "an unknown option errors via the default :restrict"
        (is (= {:exit 1 :cause :restrict}
               (bb "-cp" "test-resources" "-e"
                   "(require '[babashka.cli :as cli])
                    (binding [cli/*exit-fn* (fn [m] (prn (select-keys m [:exit :cause])))
                              *command-line-args* [\"--nope\"]]
                      (babashka.tasks/run (quote foo)))"))))
      (testing "a stray positional errors via the default :restrict-args"
        (is (= {:exit 1 :cause :restrict-args}
               (bb "-cp" "test-resources" "-e"
                   "(require '[babashka.cli :as cli])
                    (binding [cli/*exit-fn* (fn [m] (prn (select-keys m [:exit :cause])))
                              *command-line-args* [\"prod\" \"extra\"]]
                      (babashka.tasks/run (quote dep)))"))))))
  (testing "a symbol :cli entry resolves to a defaults var, functions included"
    (test-utils/with-config '{:tasks {:cli babashka.tasks-cli/base-opts
                                      deploy {:cmd {"go" {:exec-fn babashka.tasks-cli/deploy-x}}}
                                      s {:exec-fn babashka.tasks-cli/strict}}}
      (testing "the happy path is unaffected"
        (is (= {:env "prod" :ran :exec-only}
               (bb "-cp" "test-resources" "deploy" "go" "prod"))))
      (testing "no command on a handler-less group goes through the defaults :error-fn"
        ;; :msg is populated for the dispatch-level cause too (not just :cause),
        ;; like it is for option errors, so a handler can just read :msg
        (is (thrown-with-msg?
             Exception #"DEFAULTS-ERR :input-exhausted \| No command given\."
             (test-utils/bb nil "-cp" "test-resources" "deploy"))))
      (testing "an unknown command goes through the defaults :error-fn"
        ;; with :restrict-args among the defaults the unmatched word is
        ;; rejected as a stray argument, not as :no-match; a known command
        ;; word is exempt from :restrict-args
        (is (thrown-with-msg?
             Exception #"DEFAULTS-ERR :restrict-args"
             (test-utils/bb nil "-cp" "test-resources" "deploy" "bogus"))))
      (testing "a function's own :error-fn wins over the defaults for its errors"
        (is (thrown-with-msg?
             Exception #"LEAF-ERR :require"
             (test-utils/bb nil "-cp" "test-resources" "s")))))
    (testing "a symbol :cli entry that does not name a map is an error"
      (test-utils/with-config '{:tasks {:cli babashka.tasks-cli/deploy-x
                                        deploy {:cmd {"go" {:exec-fn babashka.tasks-cli/deploy-x}}}}}
        (is (thrown-with-msg?
             Exception #":tasks :cli babashka.tasks-cli/deploy-x is not a map"
             (test-utils/bb nil "-cp" "test-resources" "deploy" "go" "prod")))))
    (testing "a :cli entry that is neither a map nor a symbol is a clear error, not a raw exception"
      (test-utils/with-config '{:tasks {:cli "oops"
                                        deploy {:cmd {"go" {:exec-fn babashka.tasks-cli/deploy-x}}}}}
        (is (thrown-with-msg?
             Exception #":tasks :cli must be a map or a symbol naming a def"
             (test-utils/bb nil "-cp" "test-resources" "deploy" "go" "prod"))))))
  (testing "dispatch errors reach a rebound *exit-fn*"
    (test-utils/with-config '{:tasks {deps {:cmd {"x" {:fn clojure.core/prn}}}}}
      (is (= {:exit 1 :cause :input-exhausted}
             (bb "-e" "(require '[babashka.cli :as cli])
                       (binding [cli/*exit-fn* (fn [m] (prn (select-keys m [:exit :cause])))]
                         (babashka.tasks/run 'deps))")))))
  (testing "a :cmd subcommand runs :depends, --help does not"
    (test-utils/with-config '{:tasks {dep {:task (println "DEP-RAN")}
                                      deps {:depends [dep]
                                            :cmd {"x" {:fn clojure.core/prn}}}}}
      (is (not (str/includes? (test-utils/bb nil "deps" "--help") "DEP-RAN")))
      (is (str/includes? (test-utils/bb nil "deps" "x") "DEP-RAN"))))
  (testing "a CLI task :doc defaults to the fn's docstring"
    (test-utils/with-config '{:tasks {foo {:exec-fn babashka.tasks-cli/run-dev}}}
      (is (str/includes? (test-utils/bb nil "-cp" "test-resources" "tasks")
                         "Runs the dev system"))))
  (testing "a task :doc and :epilog show in the root --help, written flat"
    ;; the shape lread's rewrite-clj bb.edn uses
    (test-utils/with-config '{:tasks {ci {:doc "Run the CI tests"
                                          :cli {:epilog "Runs everything by default."}
                                          :cmd {"matrix" {:fn babashka.tasks-cli/outdated}}
                                          :exec-fn babashka.tasks-cli/run-dev}}}
      (let [help (test-utils/bb nil "-cp" "test-resources" "ci" "--help")]
        (is (str/includes? help "Run the CI tests"))
        (is (str/includes? help "Runs everything by default."))
        (testing "task :doc wins over the root fn docstring"
          (is (not (str/includes? help "Runs the dev system")))))))
  (testing "a task :doc vector of lines joins with newlines"
    (test-utils/with-config '{:tasks {ci {:doc ["Run the CI tests"
                                                ""
                                                "Uses the matrix from ci.edn."]
                                          :exec-fn babashka.tasks-cli/run-dev}}}
      (is (str/includes? (test-utils/bb nil "-cp" "test-resources" "tasks")
                         "ci  Run the CI tests\n"))
      (is (str/includes? (test-utils/bb nil "-cp" "test-resources" "ci" "--help")
                         "Run the CI tests\n\nUses the matrix from ci.edn."))))
  (testing "an :exec-fn node calls the fn with opts only, spec from meta"
    (test-utils/with-config '{:tasks {foo {:exec-fn babashka.tasks-cli/deploy-x}}}
      (is (= {:env "prod" :ran :exec-only}
             (bb "-cp" "test-resources" "foo" "prod")))
      (let [help (test-utils/bb nil "-cp" "test-resources" "foo" "--help")]
        (is (str/includes? help "Usage: bb foo"))
        (is (str/includes? help "<env>")))))
  (testing "a :cmd subcommand :exec-fn is called with opts only, spec from meta"
    (test-utils/with-config '{:tasks {deploy {:cmd {"go" {:exec-fn babashka.tasks-cli/deploy-x}}}}}
      (is (= {:env "prod" :ran :exec-only}
             (bb "-cp" "test-resources" "deploy" "go" "prod")))
      (is (str/includes? (test-utils/bb nil "-cp" "test-resources" "deploy" "go" "--help")
                         "Usage: bb deploy go")))))

(deftest task-completion-test
  (testing "task-name completion lists matching public tasks"
    (test-utils/with-config '{:tasks {dev    {:exec-fn babashka.tasks-cli/run-dev}
                                      deploy {:task (println :x)}
                                      -priv  {:task (println :y)}}}
      (let [out (test-utils/bb nil "-cp" "test-resources"
                               "org.babashka.cli/completions" "complete" "--shell" "zsh" "--" "de")]
        (is (str/includes? out "dev"))
        (is (str/includes? out "deploy"))
        (is (not (str/includes? out "-priv")))
        (testing "a task without a literal :doc gets its doc from the fn"
          (is (str/includes? out "dev\tRuns the dev system")))
        (testing "files complete next to task names"
          (is (str/includes? out "org.babashka.cli/file-completion"))))
      (testing "a dash-prefixed word completes bb's global options"
        (let [out (test-utils/bb nil "-cp" "test-resources"
                                 "org.babashka.cli/completions" "complete" "--shell" "zsh" "--" "--c")]
          (is (str/includes? out "--config"))
          (is (str/includes? out "--classpath"))
          (is (not (str/includes? out "dev")))))))
  (testing "a multi-line :doc contributes one candidate, not one per line"
    (test-utils/with-config '{:tasks {ci    {:doc ["Run the CI tests" "" "Uses the matrix from ci.edn."]
                                             :task (println :ci)}
                                      other {:task (println :o)}}}
      (let [lines (str/split-lines
                   (test-utils/bb nil "org.babashka.cli/completions"
                                  "complete" "--shell" "zsh" "--" ""))]
        (is (some #(= "ci\tRun the CI tests" %) lines))
        (is (not (some #(str/includes? % "Uses the matrix") lines)))
        (is (= #{"ci" "other" "org.babashka.cli/file-completion"}
               (set (map #(first (str/split % #"\t")) (remove str/blank? lines))))))))
  (testing "a bb.edn config error still leaves the shell file completion"
    (doseq [cfg ['{:tasks {foo {:cli babashka.tasks-cli/base-opts}}}
                 '{:tasks {foo {:cmd bar}}}]]
      (test-utils/with-config cfg
        (let [out (try (test-utils/bb nil "org.babashka.cli/completions"
                                      "complete" "--shell" "zsh" "--" "")
                       (catch Exception e (:stdout (ex-data e))))]
          (is (str/includes? out "org.babashka.cli/file-completion"))))))
  (testing "completion never runs what is on the line being completed"
    (test-utils/with-config '{:tasks {foo {:task (println :x)}}}
      (let [marker (fs/file (fs/temp-dir) "bb-completion-must-not-write.txt")]
        (fs/delete-if-exists marker)
        (testing "an -e expression is not evaluated"
          (test-utils/bb nil "org.babashka.cli/completions" "complete" "--shell" "zsh" "--"
                         "-e" (format "(spit \"%s\" :executed)" marker) "")
          (is (not (fs/exists? marker))))
        (testing "and --version prints no candidates"
          (let [out (test-utils/bb nil "org.babashka.cli/completions"
                                   "complete" "--shell" "zsh" "--" "--version" "")]
            (is (not (str/includes? out "babashka v"))))))))
  (testing "a --fresh in the completed line is not read as bb's own"
    (test-utils/with-config '{:tasks {foo {:task (println :x)}
                                      bar {:task (println :y)}}}
      (let [out (test-utils/bb nil "org.babashka.cli/completions"
                               "complete" "--shell" "zsh" "--" "--fresh" "true" "f")]
        (testing "so the partial word stays the last one"
          (is (str/includes? out "foo"))
          (is (not (str/includes? out "bar")))))))
  (testing "a namespace that prints when it loads does not become a candidate"
    (test-utils/with-config '{:tasks {noisy {:exec-fn babashka.tasks-cli-noisy/go}}}
      (testing "option completion"
        (let [out (test-utils/bb nil "-cp" "test-resources"
                                 "org.babashka.cli/completions" "complete" "--shell" "zsh" "--" "noisy" "-")]
          (is (str/includes? out "--port"))
          (is (not (str/includes? out "LOAD NOISE")))))
      (testing "task-name completion, where the doc is derived"
        (let [out (test-utils/bb nil "-cp" "test-resources"
                                 "org.babashka.cli/completions" "complete" "--shell" "zsh" "--" "no")]
          (is (str/includes? out "noisy"))
          (is (not (str/includes? out "LOAD NOISE")))))
      (testing "and bb tasks stays clean too"
        (let [out (test-utils/bb nil "-cp" "test-resources" "tasks")]
          (is (str/includes? out "Runs it"))
          (is (not (str/includes? out "LOAD NOISE")))))))
  (testing "a task without :cli defers argument completion to the shell"
    (test-utils/with-config '{:tasks {plain {:task (println :x)}}}
      (let [out (test-utils/bb nil "org.babashka.cli/completions"
                               "complete" "--shell" "zsh" "--" "plain" "")]
        (is (str/includes? out "org.babashka.cli/file-completion")))))
  (testing "completion resolves a handler that lives on the task's :extra-paths"
    (test-utils/with-config '{:tasks {foo {:extra-paths ["test-resources"]
                                           :exec-fn babashka.tasks-cli/deploy-x}}}
      (let [out (test-utils/bb nil "org.babashka.cli/completions"
                               "complete" "--shell" "zsh" "--" "foo" "-")]
        (is (str/includes? out "--env")))))
  (testing "completion resolves a handler named through a :requires alias"
    (test-utils/with-config '{:tasks {foo {:requires ([babashka.tasks-cli :as t])
                                           :exec-fn t/deploy-x}}}
      (let [out (test-utils/bb nil "-cp" "test-resources"
                               "org.babashka.cli/completions" "complete" "--shell" "zsh" "--" "foo" "-")]
        (is (str/includes? out "--env")))))
  (testing "completion falls back to files when the task's setup fails"
    (test-utils/with-config '{:tasks {broken {:requires ([no.such.ns :as n])
                                              :exec-fn babashka.tasks-cli/deploy-x}}}
      (let [out (test-utils/bb nil "-cp" "test-resources"
                               "org.babashka.cli/completions" "complete" "--shell" "zsh" "--" "broken" "-")]
        (is (str/includes? out "org.babashka.cli/file-completion")))))
  (testing "a task whose handler namespace is missing does not sink the whole listing"
    (test-utils/with-config '{:tasks {broken {:requires ([no.such.ns])
                                              :exec-fn no.such.ns/handler}
                                      other  {:task (println :x)}}}
      (testing "task-name completion still offers every candidate"
        (let [out (test-utils/bb nil "org.babashka.cli/completions"
                                 "complete" "--shell" "zsh" "--" "")]
          (is (str/includes? out "broken"))
          (is (str/includes? out "other"))
          (is (str/includes? out "org.babashka.cli/file-completion"))))
      (testing "and bb tasks still lists them"
        (let [out (test-utils/bb nil "tasks")]
          (is (str/includes? out "broken"))
          (is (str/includes? out "other"))))))
  (testing "completion honors a runner-level :help false, like dispatch does"
    (test-utils/with-config '{:tasks {:cli {:help false}
                                      foo {:exec-fn babashka.tasks-cli/deploy-x}}}
      (let [out (test-utils/bb nil "-cp" "test-resources"
                               "org.babashka.cli/completions" "complete" "--shell" "zsh" "--" "foo" "-")]
        (is (not (str/includes? out "--help"))))))
  (testing "root option completion delegates to dispatch via fn meta"
    (test-utils/with-config '{:tasks {dev {:exec-fn babashka.tasks-cli/run-dev}}}
      (let [out (test-utils/bb nil "-cp" "test-resources"
                               "org.babashka.cli/completions" "complete" "--shell" "zsh" "--" "dev" "--")]
        (is (str/includes? out "--port"))
        (is (str/includes? out "--help")))))
  (testing ":cli :cmd subcommand option completion pulls the fn's spec from meta"
    (test-utils/with-config '{:tasks {deploy {:cmd {"lock" {:fn babashka.tasks-cli/lock}}}}}
      (let [out (test-utils/bb nil "-cp" "test-resources"
                               "org.babashka.cli/completions" "complete" "--shell" "zsh" "--" "deploy" "lock" "-")]
        (is (str/includes? out "--message"))
        (is (str/includes? out "--environment")))))
  (testing "a symbol runner-level :cli entry is resolved for completion, not just for dispatch"
    (test-utils/with-config '{:tasks {:cli babashka.tasks-cli/base-opts
                                      deploy {:cmd {"lock" {:fn babashka.tasks-cli/lock}}}}}
      (testing "subcommand completion still finds the leaf's own options"
        (let [out (test-utils/bb nil "-cp" "test-resources"
                                 "org.babashka.cli/completions" "complete" "--shell" "zsh" "--" "deploy" "")]
          (is (str/includes? out "lock"))
          (is (not (str/includes? out "org.babashka.cli/file-completion")))))
      (testing "a :spec on the defaults var completes too, alongside the leaf's own"
        (let [out (test-utils/bb nil "-cp" "test-resources"
                                 "org.babashka.cli/completions" "complete" "--shell" "zsh" "--" "deploy" "lock" "-")]
          (is (str/includes? out "--verbose"))
          (is (str/includes? out "--message"))))))
  (testing "--fresh true acts as a trailing empty word (powershell drops empty args)"
    (test-utils/with-config '{:tasks {deploy {:cmd {"lock" {:fn babashka.tasks-cli/lock}}}}}
      (let [out (test-utils/bb nil "-cp" "test-resources"
                               "org.babashka.cli/completions" "complete" "--shell" "powershell" "--fresh" "true" "--" "deploy" "lock")]
        ;; a fresh word completes the positional's values, not option names
        (is (str/includes? out "production"))
        (is (str/includes? out "staging"))
        (is (not (str/includes? out "--message"))))))
  (testing "zsh snippet installs for bb"
    (test-utils/with-config '{:tasks {dev {:task (println :x)}}}
      (is (str/includes? (test-utils/bb nil "org.babashka.cli/completions" "snippet" "--shell" "zsh")
                         "#compdef bb")))))
