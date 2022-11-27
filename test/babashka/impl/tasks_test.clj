(ns babashka.impl.tasks-test
  (:require [babashka.impl.tasks :as sut]
            [clojure.test :as t]))

(t/deftest target-order-test
  (t/is (= '[quux bar foo]
           (sut/target-order
            {'foo {:depends ['bar 'quux]}
             'bar {:depends ['quux]}}
            'foo))))

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
