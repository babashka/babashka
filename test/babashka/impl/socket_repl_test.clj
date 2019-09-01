(ns babashka.impl.socket-repl-test
  (:require
   [babashka.impl.socket-repl :refer [start-repl! stop-repl!]]
   [babashka.test-utils :as tu]
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is testing]]))

(deftest socket-repl-test
  (when tu/jvm?
    (start-repl! "0.0.0.0:1666" {:env (atom {})})
    (is (str/includes? (:out (sh "bash" "-c"
                                 "echo \"(+ 1 2 3)\n:repl/exit\" | nc 127.0.0.1 1666"))
                       "bb=> 6"))
    (testing "ctrl-d exits normally, doesn't print nil"
      (is (str/ends-with? (:out (sh "bash" "-c"
                                   "echo \"(inc 1336)\" | nc 127.0.0.1 1666"))
                          "1337\nbb=> ")))
    (stop-repl!)))

;;;; Scratch

(comment
  (socket-repl-test)
  )
