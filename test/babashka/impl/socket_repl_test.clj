(ns babashka.impl.socket-repl-test
  (:require
   [babashka.impl.common :as common]
   [babashka.impl.server :refer [clojure-core-server-namespace]]
   [babashka.impl.socket-repl :refer [start-repl! stop-repl!]]
   [babashka.process :as p]
   [babashka.test-utils :as tu]
   [babashka.wait :as w]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is testing]]
   [sci.ctx-store :as ctx-store]
   [sci.impl.opts :refer [init]]))

(set! *warn-on-reflection* true)

(defn socket-command [expr expected]
  (with-open [socket (java.net.Socket. "127.0.0.1" 1666)
              reader (io/reader socket)
              sw (java.io.StringWriter.)
              writer (io/writer socket)]
    (binding [*out* writer]
      (println (str expr "\n")))
    (loop []
      (when-let [l (try (.readLine ^java.io.BufferedReader reader)
                        (catch java.net.SocketException _ nil))]
        ;; (prn :l l)
        (binding [*out* sw]
          (println l))
        (let [s (str sw)]
          ;; (prn :s s :expected expected (str/includes? s expected))
          (if (if (fn? expected)
                (expected s)
                (str/includes? s expected))
            (is true)
            (recur)))))
    (binding [*out* writer]
      (println ":repl/quit\n"))
    :success))

(def server-process (volatile! nil))

(def exec? (System/getenv "BABASHKA_SOCKET_REPL_TEST"))

(deftest socket-repl-test
  (when exec?
    (try
      (if tu/jvm?
        (let [ctx (init {:namespaces {'clojure.core.server clojure-core-server-namespace}
                         :features #{:bb}})]
          (ctx-store/reset-ctx! ctx)
          (start-repl! "0.0.0.0:1666" ctx))
        (do (vreset! server-process
                     (p/process ["./bb" "socket-repl" "localhost:1666"]))
            (w/wait-for-port "localhost" 1666)))
      (Thread/sleep 50)
      (is (socket-command "(+ 1 2 3)" "user=> 6"))
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
          (do (stop-repl!)
              (ctx-store/reset-ctx! nil)
              (Thread/sleep 100))
          (p/destroy-tree @server-process))))))

(deftest socket-repl-opts-test
  (when exec?
    (try
      (if tu/jvm?
        (let [ctx (init {:bindings {'*command-line-args*
                                    ["a" "b" "c"]}
                         :env (atom {})
                         :namespaces {'clojure.core.server clojure-core-server-namespace}
                         :features #{:bb}})]
          (ctx-store/reset-ctx! ctx)
          (start-repl! "{:address \"localhost\" :accept clojure.core.server/repl :port 1666}"
                       ctx))
        (do (vreset! server-process
                     (p/process ["./bb" "--socket-repl" "{:address \"localhost\" :accept clojure.core.server/repl :port 1666}"]))
            (w/wait-for-port "localhost" 1666)))
      (Thread/sleep 50)
      (is (socket-command "(+ 1 2 3)" "user=> 6"))
      (finally
        (if tu/jvm?
          (do (stop-repl!)
              (ctx-store/reset-ctx! nil)
              (Thread/sleep 100))
          (p/destroy-tree @server-process))))))

(deftest socket-prepl-test
  (when exec?
    (try
      (if tu/jvm?
        (let [ctx (init {:bindings {'*command-line-args*
                                    ["a" "b" "c"]}
                         :env (atom {})
                         :namespaces {'clojure.core.server clojure-core-server-namespace}
                         :features #{:bb}})]
          (vreset! common/ctx (fn [] ctx))
          (start-repl! "{:address \"localhost\" :accept clojure.core.server/io-prepl :port 1666}"
                       ctx))
        (do (vreset! server-process
                     (p/process ["./bb" "--socket-repl" "{:address \"localhost\" :accept clojure.core.server/io-prepl :port 1666}"]))
            (w/wait-for-port "localhost" 1666)))
      (Thread/sleep 50)
      (is (socket-command "(+ 1 2 3)" (fn [s]
                                        (let [m (edn/read-string s)]
                                          (and (= "6" (:val m))
                                               (= "user" (:ns m))
                                               (= "(+ 1 2 3)" (:form m)))))))
      (finally
        (if tu/jvm?
          (do (stop-repl!)
              (vreset! common/ctx nil)
              (Thread/sleep 100))
          (p/destroy-tree @server-process))))))

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
