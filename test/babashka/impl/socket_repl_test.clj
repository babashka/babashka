(ns babashka.impl.socket-repl-test
  (:require
   [babashka.impl.common :as common]
   [babashka.impl.socket-repl :refer [start-repl! stop-repl!]]
   [babashka.main :refer [clojure-core-server]]
   [babashka.test-utils :as tu]
   [clojure.java.io :as io]
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is testing]]
   [sci.impl.opts :refer [init]]))

(set! *warn-on-reflection* true)

(defn socket-command [expr expected]
  (with-open [socket (java.net.Socket. "127.0.0.1" 1666)
              reader (io/reader socket)
              sw (java.io.StringWriter.)
              writer (io/writer socket)]
    (binding [*out* writer]
      (println (str expr))
      (println ":repl/quit\n"))
    (loop []
      (when-let [l (.readLine ^java.io.BufferedReader reader)]
        ;; (prn :l l)
        (binding [*out* sw]
          (println l))
        (recur)))
    (let [s (str sw)]
      (if (fn? expected)
        (is (expected s))
        (is (str/includes? s expected)
            (format "\"%s\" does not contain \"%s\""
                    s expected)))
      s)))

(deftest socket-repl-test
  (try
    (if tu/jvm?
      (let [ctx (init {:bindings {'*command-line-args*
                                  ["a" "b" "c"]}
                       :env (atom {})
                       :namespaces {'clojure.core.server clojure-core-server}
                       :features #{:bb}})]
        (vreset! common/ctx ctx)
        (start-repl! "0.0.0.0:1666" ctx))
      (future
        (sh "bash" "-c"
            "echo '[1 2 3]' | ./bb --socket-repl 0.0.0.0:1666 a b c")))
    ;; wait for server to be available
    (when tu/native?
      (while (not (zero? (:exit
                          (sh "bash" "-c"
                              "lsof -t -i:1666"))))))
    (is (socket-command "(+ 1 2 3)" "user=> 6"))
    (testing "*command-line-args*"
      (is (socket-command '*command-line-args* "\"a\" \"b\" \"c\"")))
    (testing "&env"
      (socket-command "(defmacro bindings [] (mapv #(list 'quote %) (keys &env)))" "bindings")
      (socket-command "(defn bar [x y z] (bindings))" "bar")
      (is (socket-command "(bar 1 2 3)" "[x y z]")))
    (testing "reader conditionals"
      (is (socket-command "#?(:bb 1337 :clj 8888)" "1337")))
    (testing "*1, *2, *3, *e"
      (is (socket-command "1\n*1" "1")))
    (testing "*ns*"
      (is (socket-command "(ns foo.bar) (ns-name *ns*)" "foo.bar")))
    (finally
      (if tu/jvm?
        (stop-repl!)
        (sh "bash" "-c"
            "kill -9 $(lsof -t -i:1666)")))))

(deftest socket-repl-opts-test
  (try
    (if tu/jvm?
      (let [ctx (init {:bindings {'*command-line-args*
                                  ["a" "b" "c"]}
                       :env (atom {})
                       :namespaces {'clojure.core.server clojure-core-server}
                       :features #{:bb}})]
        (vreset! common/ctx ctx)
        (start-repl! "{:address \"0.0.0.0\" :accept clojure.core.server/repl :port 1666}"
                     ctx))
      (future
        (sh "bash" "-c"
            "./bb --socket-repl {:address \"0.0.0.0\" :accept clojure.core.server/repl :port 1666}")))
    ;; wait for server to be available
    (when tu/native?
      (while (not (zero? (:exit
                          (sh "bash" "-c"
                              "lsof -t -i:1666"))))))
    (is (socket-command "(+ 1 2 3)" "user=> 6"))
    (finally
      (if tu/jvm?
        (stop-repl!)
        (sh "bash" "-c"
            "kill -9 $(lsof -t -i:1666)")))))

(deftest socket-prepl-test
  (try
    (if tu/jvm?
      (let [ctx (init {:bindings {'*command-line-args*
                                  ["a" "b" "c"]}
                       :env (atom {})
                       :namespaces {'clojure.core.server clojure-core-server}
                       :features #{:bb}})]
        (vreset! common/ctx ctx)
        (start-repl! "{:address \"0.0.0.0\" :accept clojure.core.server/io-prepl :port 1666}"
                     ctx))
      (future
        (sh "bash" "-c"
            "./bb --socket-repl {:address \"0.0.0.0\" :accept clojure.core.server/io-prepl :port 1666}")))
    ;; wait for server to be available
    (when tu/native?
      (while (not (zero? (:exit
                          (sh "bash" "-c"
                              "lsof -t -i:1666"))))))
    (is (socket-command "(+ 1 2 3)" (fn [s]
                                      (and (str/includes? s ":val \"6\"")
                                           (str/includes? s ":ns \"user\"")
                                           (str/includes? s ":form \"(+ 1 2 3)\"")))))
    (finally
      (if tu/jvm?
        (stop-repl!)
        (sh "bash" "-c"
            "kill -9 $(lsof -t -i:1666)")))))

;;;; Scratch

(comment
  (socket-repl-test)
  (dotimes [_ 1000]
    (t/run-tests))
  (stop-repl!)
  (start-repl! "0.0.0.0:1666" {:bindings {(with-meta '*input*
                                            {:sci/deref! true})
                                          (delay [1 2 3])
                                          '*command-line-args*
                                          ["a" "b" "c"]}
                               :env (atom {})})
  (socket-command "(+ 1 2 3)" "6")
  )
