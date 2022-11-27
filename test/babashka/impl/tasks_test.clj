(ns babashka.impl.tasks-test
  (:require  [babashka.impl.common :refer [bb-edn]]
             [babashka.impl.tasks :as sut]
             [clojure.string :as str]
             [clojure.test :as t]
             [sci.core :as sci]))

(t/deftest target-order-test
  (t/is (= '[quux bar foo]
           (sut/target-order
            {'foo {:depends ['bar 'quux]}
             'bar {:depends ['quux]}}
            'foo))))

(defmacro with-bb-edn
  "Helper macro to execute BODY reseting BB-EDN to EDN (including :raw)
  for the duration of the call."
  [edn & body]
  `(let [old# @bb-edn]
     (try
       (vreset! bb-edn (assoc ~edn :raw (pr-str ~edn)))
       ~@body
       (finally
         (vreset! bb-edn old#)))))

(t/deftest tasks-list
  (t/testing "empty tasks list"
    (let [sci-ctx (sci/init {})]
      (with-bb-edn {:tasks {}}
        (t/is (= ["No tasks found."]
                 (str/split-lines  (with-out-str (sut/list-tasks sci-ctx))))))
      (with-bb-edn {:tasks {}}
        (t/is (= ["No tasks found."]
                 (str/split-lines  (with-out-str (sut/list-tasks sci-ctx))))))
      (with-bb-edn {:tasks {:x 1}}
        (t/is (= ["No tasks found."]
                 (str/split-lines  (with-out-str (sut/list-tasks sci-ctx))))))
      (with-bb-edn {:tasks {'-xyz 5}}
        (t/is (= ["No tasks found."]
                 (str/split-lines  (with-out-str (sut/list-tasks sci-ctx))))))
      (with-bb-edn {:tasks {'xyz {:private true}}}
        (t/is (= ["No tasks found."]
                 (str/split-lines  (with-out-str (sut/list-tasks sci-ctx))))))
      ;; sanity check for this test
      (with-bb-edn {:tasks {'xyz {:private false}}}
        (t/is (= ["The following tasks are available:" "" "xyz"]
                 (str/split-lines  (with-out-str (sut/list-tasks sci-ctx))))))))

  (t/testing "some tasks"
    (let [sci-ctx (sci/init {})]
      (with-bb-edn {:tasks {'abc 1
                            'xyz 2}}
        (t/is (= ["The following tasks are available:" "" "abc" "xyz"]
                 (str/split-lines  (with-out-str (sut/list-tasks sci-ctx))))))
      (with-bb-edn {:tasks {'abc 1
                            'xyz {:doc "some text"
                                  :tasks 5}
                            '-xyz 3
                            'qrs {:private true}}}
        (t/is (= ["The following tasks are available:" "" "abc" "xyz some text"]
                 (str/split-lines  (with-out-str (sut/list-tasks sci-ctx))))))
      (with-bb-edn {:tasks {'xyz 2
                            'abc 2}}
        (t/is (= ["The following tasks are available:" "" "xyz" "abc"]
                 (str/split-lines  (with-out-str (sut/list-tasks sci-ctx)))))))))

(t/deftest key-order-test
  (let [edn "{:tasks
 {;; Development tasks
  repl        {:doc  \"Starts an nrepl session with a reveal window\"
               :task (clojure \"-M:reveal-nrepl\")}

  ;; Testing
  watch-tests {:doc  \"Watch tests and run on change\"
               :task (clojure \"-M:test -m kaocha.runner --watch\")}
  ;test
  #_{:doc  \"Runs tests\"
   :task (clojure \"-M:test -m kaocha.runner\")}
  }}"]
    (t/is (= '[repl watch-tests] (sut/key-order edn)))))
