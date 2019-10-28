(ns babashka.impl.socket-repl-test
  (:require
   [babashka.impl.socket-repl :refer [start-repl! stop-repl!]]
   [babashka.test-utils :as tu]
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is testing]]))

(def mac?
  (str/includes?
   (str/lower-case (System/getProperty "os.name"))
   "mac"))

(defn socket-command [expr]
  (let [expr (format "echo \"%s\n:repl/exit\" | nc 127.0.0.1 1666"
                     (pr-str expr))
        ret (sh "bash" "-c"
                expr)]
    (:out ret)))

(deftest socket-repl-test
  (try
    (if tu/jvm?
      (start-repl! "0.0.0.0:1666" {:bindings {(with-meta '*in*
                                                {:sci/deref! true})
                                              (delay [1 2 3])
                                              '*command-line-args*
                                              ["a" "b" "c"]}
                                   :env (atom {})})
      (future
        (sh "bash" "-c"
            "echo '[1 2 3]' | ./bb --socket-repl 0.0.0.0:1666 a b c")))
    ;; wait for server to be available
    (when tu/native?
      (while (not (zero? (:exit
                          (sh "bash" "-c"
                              "lsof -t -i:1666"))))))
    (is (str/includes? (socket-command '(+ 1 2 3))
                       "bb=> 6"))
    (testing "ctrl-d exits normally, doesn't print nil"
      (is (str/ends-with? (:out (sh "bash" "-c"
                                    (if mac? ;; mac doesn't support -q
                                      "echo \"(inc 1336)\" | nc 127.0.0.1 1666"
                                      "echo \"(inc 1336)\" | nc -q 1 127.0.0.1 1666")))
                          "1337\nbb=> ")))
    (testing "*in*"
      (is (str/includes? (socket-command "*in*")
                         "[1 2 3]")))
    (testing "*command-line-args*"
      (is (str/includes? (socket-command '*command-line-args*)
                         "\"a\" \"b\" \"c\"")))
    (testing "&env"
      (socket-command '(defmacro bindings [] (mapv #(list 'quote %) (keys &env))))
      (socket-command '(defn bar [x y z] (bindings)))
      (is (str/includes? (socket-command '(bar 1 2 3))
                         "[x y z]")))
    (testing "reader conditionals"
      (is (str/includes? (let [ret (sh "bash" "-c"
                                       (format "echo \"%s\n:repl/exit\" | nc 127.0.0.1 1666"
                                               "#?(:bb 1337 :clj 8888)"))]
                           (:out ret))
                         "1337")))
    (testing "*1, *2, *3, *e"
      (is (= 2 (count (re-seq #"1\n" (let [ret (sh "bash" "-c"
                                                   (format "echo \"%s\n*1\n:repl/exit\" | nc 127.0.0.1 1666"
                                                           "1"))]
                                       (:out ret)))))))
    (finally
      (if tu/jvm?
        (stop-repl!)
        (sh "bash" "-c"
            "kill -9 $(lsof -t -i:1666)")))))

;;;; Scratch

(comment
  (socket-repl-test)
  )
