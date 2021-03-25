(ns babashka.bb-edn-test
  {:clj-kondo/config '{:linters {:unresolved-symbol {:exclude [working?]}}}}
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
     (spit bb-edn-file# ~cfg)
     (binding [test-utils/*bb-edn-path* (str bb-edn-file#)]
       ~@body)))

(deftest task-cli-test
  (with-config {}
    (is (thrown-with-msg?
         Exception #"Task does not exist: :sum"
         (bb :sum)))))

(deftest babashka-task-test
  (with-config {:tasks {:sum [:babashka "-e" "(+ 1 2 3)"]}}
    (let [res (bb :sum)]
      (is (= 6 res)))))

(deftest shell-task-test
  (let [temp-dir (fs/create-temp-dir)
        temp-file (fs/create-file (fs/path temp-dir "temp-file.txt"))]
    (with-config {:tasks {:clean [:shell "rm" (str temp-file)]}}
      (is (fs/exists? temp-file))
      (bb :clean)
      (is (not (fs/exists? temp-file))))))

(deftest sequential-task-test
  (testing ":and-do"
    (let [temp-dir (fs/create-temp-dir)
          temp-file (fs/create-file (fs/path temp-dir "temp-file.txt"))]
      (with-config {:tasks {:sum [:babashka "-e" "(+ 1 2 3)"]
                            :all [:do
                                  [:shell "rm" (str temp-file)]
                                  [:sum]]}}
        (is (fs/exists? temp-file))
        (let [res (bb :all)]
          (is (= 6 res)))
        (is (not (fs/exists? temp-file)))))
    #_(testing ":and-do shortcut"
      (let [temp-dir (fs/create-temp-dir)
            temp-file (fs/create-file (fs/path temp-dir "temp-file.txt"))]
        (with-config {:tasks {:clean [:shell :clean:sum]
                              :sum [:babashka "-e" "(+ 1 2 3)"]
                              :all [:babashka :clean:sum]}}
          (is (fs/exists? temp-file))
          (let [res (bb :clean:sum)]
            (is (= 6 res)))
          (is (not (fs/exists? temp-file)))))))
  #_(testing ":do always continuing"
    (with-config {:tasks {:sum-1 [:babashka "-e" "(do (+ 4 5 6) nil)"]
                          :sum-2 [:babashka "-e" "(+ 1 2 3)"]
                          :all [:babashka :sum-1:sum2]}}
      (is (= 6 (bb :all))))
    #_(with-config {:tasks {:div-by-zero [:babashka "-e" "(/ 1 0)"]
                          :sum [:babashka "-e" "(+ 1 2 3)"]
                          :all[:babashka :div-by-zero:sum] }}
      (is (= 6 (bb :all)))))
  (testing "task fails when one of subtask fails"
    (with-config {:tasks {:div-by-zero [:babashka "-e" "(/ 1 0)"]
                          :sum [:babashka "-e" "(+ 1 2 3)"]
                          :all [:babashka :div-by-zero:sum]}}
      (is (thrown-with-msg? Exception #"Divide"
                            (bb :all)))))
  #_(testing ":or-do short-cutting"
    (with-config {:tasks {:sum-1 [:babashka "-e" "(+ 1 2 3)"]
                          :sum-2 [:babashka "-e" "(+ 4 5 6)"]
                          :all [:or [:sum1] [:sum2]]}}
      (is (= 6 (bb :all)))))
  #_(testing ":or-do succeeding after failing"
    (with-config {:tasks {:div-by-zero [:babashka "-e" "(/ 1 0)"]
                          :sum [:babashka "-e" "(+ 1 2 3)"]
                          :all [:babashka [:or [:div-by-zero] [:sum]]]}}
      (is (= 6 (bb :all))))))

(deftest prioritize-user-task-test
  (is (map? (bb :describe)))
  (with-config {:tasks {:describe [:babashka "-e" "(+ 1 2 3)"]}}
    (is (= 6 (bb :describe)))))

(deftest help-task-test
  (with-config "{:tasks {:cool-task
                        ^{:help \"Usage: bb :cool-task

    Addition is a pretty advanced topic.  Let us start with the identity element
    0. ...\"}
                        [:babashka -e (+ 1 2 3)]}}"
    (is (str/includes? (apply test-utils/bb nil
                              (map str [:help :cool-task]))
                       "Usage: bb :cool-task"))))

#_(deftest list-tasks-test
  (with-config {:tasks {:cool-task-1 {:task/type :babashka
                                      :task/args ["-e" "(+ 1 2 3)"]
                                      :task/description "Return the sum of 1, 2 and 3."
                                      :task/help "Usage: bb :cool-task

Addition is a pretty advanced topic.  Let us start with the identity element
0. ..."}
                        :cool-task-2 {:task/type :babashka
                                      :task/description "Return the sum of 4, 5 and 6."
                                      :task/args ["-e" "(+ 4 5 6)"]
                                      :task/help "Usage: bb :cool-task

Addition is a pretty advanced topic.  Let us start with the identity element
0. ..."}}}
    (let [res (apply test-utils/bb nil
                     (map str [:tasks]))]
      (is (str/includes? res "The following tasks are available:"))
      (is (str/includes? res ":cool-task-1 Return the"))
      (is (str/includes? res ":cool-task-2 Return the")))))

(deftest main-task-test
  (with-config {:paths ["test-resources/task_scripts"]
                :tasks {:main-task [:main 'tasks 1 2 3]}}
    (is (= '("1" "2" "3") (bb :main-task)))
    (let [res (apply test-utils/bb nil
                     (map str [:help :main-task]))]
      (is (str/includes? res "Usage: just pass some args")))))
