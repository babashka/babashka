(ns nrepl.edn-test
  ;; BB-TEST-PATCH the server is closed through core-test's with-server, and
  ;; type hints naming classes the image does not expose are dropped.
  (:require [clojure.test :refer [deftest is testing]]
            [nrepl.core-test]
            [nrepl.core :as nrepl]
            [nrepl.server :as server]
            [nrepl.socket :as socket]
            [nrepl.test-helpers :refer [eval-value1]]
            [nrepl.transport :as transport])
  (:import
   (java.lang AutoCloseable)
   (java.nio.file Files)))

(deftest edn-transport-communication
  (nrepl.core-test/with-server [server (server/start-server :transport-fn transport/edn)]
    (with-open [^AutoCloseable conn (nrepl/connect :transport-fn transport/edn
                                                   :port (:port server))]
      (let [client (nrepl/client conn 1000)]
        (testing "op as a string value"
          (is (= 5 (eval-value1 client '(+ 2 3)))))
        (testing "op as a keyword value"
          (is (= [5] (nrepl/response-values
                      (nrepl/message client {:op :eval :code "(+ 2 3)"})))))
        (testing "simple expressions"
          (is (= (eval '(range 40)) (eval-value1 client '(range 40)))))))))

(when socket/unix-domain-flavor
  (deftest edn-transport-unix-socket
    (let [tmpdir (Files/createTempDirectory
                  (.toPath (clojure.java.io/as-file "target"))
                  "edn-socket-test-"
                  (into-array java.nio.file.attribute.FileAttribute []))
          sock-path (str tmpdir "/socket")]
      (try
        (nrepl.core-test/with-server [server (server/start-server :transport-fn transport/edn
                                                                  :socket sock-path)]
          (with-open [^AutoCloseable conn (nrepl/connect :transport-fn transport/edn
                                                         :socket sock-path)]
            (let [client (nrepl/client conn 1000)]
              (testing "basic eval over Unix socket"
                (is (= 5 (eval-value1 client '(+ 2 3)))))
              (testing "expressions returning collections"
                (is (= (range 40) (eval-value1 client '(range 40)))))
              (testing "multiple sequential evaluations"
                (is (= 10 (eval-value1 client '(* 2 5))))))))
        (finally
          (Files/deleteIfExists (.resolve tmpdir "socket"))
          (Files/deleteIfExists tmpdir))))))
