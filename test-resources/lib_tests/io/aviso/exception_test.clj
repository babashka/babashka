(ns io.aviso.exception-test
  (:use clojure.test)
  (:require [clojure.string :as str]
            [io.aviso.exception :as e :refer [*fonts* parse-exception format-exception]]
            [clojure.pprint :refer [pprint]]
            [com.stuartsierra.component :as component]
            [com.walmartlabs.test-reporting :refer [reporting]]
            io.aviso.component))

(deftest write-exceptions
  (testing "exception properties printing"
    (testing "Does not fail with ex-info's map keys not implementing clojure.lang.Named"
      (is (re-find #"string-key.*string-val"
                   (format-exception (ex-info "Error" {"string-key" "string-val"})))))))

(defn parse [& text-lines]
  (let [text (str/join \newline text-lines)]
    (binding [*fonts* nil]
      (parse-exception text nil))))

(deftest parse-exceptions
  (is (= [{:class-name "java.lang.IllegalArgumentException"
           :message    "No value supplied for key: {:host \"example.com\"}"
           :stack-trace
                       [{:simple-class   "PersistentHashMap"
                         :package        "clojure.lang"
                         :omitted        true
                         :is-clojure?    false
                         :method         "create"
                         :name           ""
                         :formatted-name "..."
                         :file           ""
                         :line           nil
                         :class          "clojure.lang.PersistentHashMap"
                         :names          []}
                        {:simple-class   "client$tcp_client"
                         :package        "riemann"
                         :is-clojure?    true
                         :method         "doInvoke"
                         :name           "riemann.client/tcp-client"
                         :formatted-name "riemann.client/tcp-client"
                         :file           "client.clj"
                         :line           90
                         :class          "riemann.client$tcp_client"
                         :names          '("riemann.client" "tcp-client")}
                        {:simple-class   "RestFn"
                         :package        "clojure.lang"
                         :omitted        true
                         :is-clojure?    false
                         :method         "invoke"
                         :name           ""
                         :formatted-name "..."
                         :file           ""
                         :line           nil
                         :class          "clojure.lang.RestFn"
                         :names          []}
                        {:simple-class   "error_monitor$make_connection"
                         :package        "com.example"
                         :is-clojure?    true
                         :method         "invoke"
                         :name           "com.example.error-monitor/make-connection"
                         :formatted-name "com.example.error-monitor/make-connection"
                         :file           "error_monitor.clj"
                         :line           22
                         :class          "com.example.error_monitor$make_connection"
                         :names          '("com.example.error-monitor" "make-connection")}
                        {:simple-class   "error_monitor$make_client"
                         :package        "com.example"
                         :is-clojure?    true
                         :method         "invoke"
                         :name           "com.example.error-monitor/make-client"
                         :formatted-name "com.example.error-monitor/make-client"
                         :file           "error_monitor.clj"
                         :line           26
                         :class          "com.example.error_monitor$make_client"
                         :names          '("com.example.error-monitor" "make-client")}
                        {:simple-class   "core$map$fn__4553"
                         :package        "clojure"
                         :is-clojure?    true
                         :method         "invoke"
                         :name           "clojure.core/map/fn"
                         :formatted-name "clojure.core/map/fn"
                         :file           "core.clj"
                         :line           2624
                         :class          "clojure.core$map$fn__4553"
                         :names          '("clojure.core" "map" "fn")}
                        {:simple-class   "LazySeq"
                         :package        "clojure.lang"
                         :omitted        true
                         :is-clojure?    false
                         :method         "sval"
                         :name           ""
                         :formatted-name "..."
                         :file           ""
                         :line           nil
                         :class          "clojure.lang.LazySeq"
                         :names          []}
                        {:simple-class   "core$seq__4128"
                         :package        "clojure"
                         :is-clojure?    true
                         :method         "invoke"
                         :name           "clojure.core/seq"
                         :formatted-name "clojure.core/seq"
                         :file           "core.clj"
                         :line           137
                         :class          "clojure.core$seq__4128"
                         :names          '("clojure.core" "seq")}
                        {:simple-class   "core$sort"
                         :package        "clojure"
                         :is-clojure?    true
                         :method         "invoke"
                         :name           "clojure.core/sort"
                         :formatted-name "clojure.core/sort"
                         :file           "core.clj"
                         :line           2981
                         :class          "clojure.core$sort"
                         :names          '("clojure.core" "sort")}
                        {:simple-class   "core$sort_by"
                         :package        "clojure"
                         :is-clojure?    true
                         :method         "invoke"
                         :name           "clojure.core/sort-by"
                         :formatted-name "clojure.core/sort-by"
                         :file           "core.clj"
                         :line           2998
                         :class          "clojure.core$sort_by"
                         :names          '("clojure.core" "sort-by")}
                        {:simple-class   "core$sort_by"
                         :package        "clojure"
                         :is-clojure?    true
                         :method         "invoke"
                         :name           "clojure.core/sort-by"
                         :formatted-name "clojure.core/sort-by"
                         :file           "core.clj"
                         :line           2996
                         :class          "clojure.core$sort_by"
                         :names          '("clojure.core" "sort-by")}
                        {:simple-class   "error_monitor$make_clients"
                         :package        "com.example"
                         :is-clojure?    true
                         :method         "invoke"
                         :name           "com.example.error-monitor/make-clients"
                         :formatted-name "com.example.error-monitor/make-clients"
                         :file           "error_monitor.clj"
                         :line           31
                         :class          "com.example.error_monitor$make_clients"
                         :names          '("com.example.error-monitor" "make-clients")}
                        {:simple-class   "error_monitor$report_and_reset"
                         :package        "com.example"
                         :is-clojure?    true
                         :method         "invoke"
                         :name           "com.example.error-monitor/report-and-reset"
                         :formatted-name "com.example.error-monitor/report-and-reset"
                         :file           "error_monitor.clj"
                         :line           185
                         :class          "com.example.error_monitor$report_and_reset"
                         :names          '("com.example.error-monitor" "report-and-reset")}
                        {:simple-class   "main$_main$fn__705"
                         :package        "com.example.error_monitor"
                         :is-clojure?    true
                         :method         "invoke"
                         :name           "com.example.error-monitor.main/-main/fn"
                         :formatted-name "com.example.error-monitor.main/-main/fn"
                         :file           "main.clj"
                         :line           19
                         :class          "com.example.error_monitor.main$_main$fn__705"
                         :names          '("com.example.error-monitor.main" "-main" "fn")}
                        {:simple-class   "main$_main"
                         :package        "com.example.error_monitor"
                         :is-clojure?    true
                         :method         "doInvoke"
                         :name           "com.example.error-monitor.main/-main"
                         :formatted-name "com.example.error-monitor.main/-main"
                         :file           "main.clj"
                         :line           16
                         :class          "com.example.error_monitor.main$_main"
                         :names          '("com.example.error-monitor.main" "-main")}
                        {:simple-class   "RestFn"
                         :package        "clojure.lang"
                         :omitted        true
                         :is-clojure?    false
                         :method         "applyTo"
                         :name           ""
                         :formatted-name "..."
                         :file           ""
                         :line           nil
                         :class          "clojure.lang.RestFn"
                         :names          []}
                        {:class          "com.example.error_monitor.main"
                         :file           ""
                         :formatted-name "com.example.error_monitor.main.main"
                         :is-clojure?    false
                         :line           nil
                         :method         "main"
                         :name           ""
                         :names          []
                         :package        "com.example.error_monitor"
                         :simple-class   "main"}]}]
         (parse "java.lang.IllegalArgumentException: No value supplied for key: {:host \"example.com\"}"
                "\tat clojure.lang.PersistentHashMap.create(PersistentHashMap.java:77)"
                "\tat riemann.client$tcp_client.doInvoke(client.clj:90)"
                "\tat clojure.lang.RestFn.invoke(RestFn.java:408)"
                "\tat com.example.error_monitor$make_connection.invoke(error_monitor.clj:22)"
                "\tat com.example.error_monitor$make_client.invoke(error_monitor.clj:26)"
                "\tat clojure.core$map$fn__4553.invoke(core.clj:2624)"
                "\tat clojure.lang.LazySeq.sval(LazySeq.java:40)"
                "\tat clojure.lang.LazySeq.seq(LazySeq.java:49)"
                "\tat clojure.lang.RT.seq(RT.java:507)"
                "\tat clojure.core$seq__4128.invoke(core.clj:137)"
                "\tat clojure.core$sort.invoke(core.clj:2981)"
                "\tat clojure.core$sort_by.invoke(core.clj:2998)"
                "\tat clojure.core$sort_by.invoke(core.clj:2996)"
                "\tat com.example.error_monitor$make_clients.invoke(error_monitor.clj:31)"
                "\tat com.example.error_monitor$report_and_reset.invoke(error_monitor.clj:185)"
                "\tat com.example.error_monitor.main$_main$fn__705.invoke(main.clj:19)"
                "\tat com.example.error_monitor.main$_main.doInvoke(main.clj:16)"
                "\tat clojure.lang.RestFn.applyTo(RestFn.java:137)"
                "\tat com.example.error_monitor.main.main(Unknown Source)"))

      (is (= [{:class-name "java.lang.RuntimeException", :message "Request handling exception"}
              {:class-name "java.lang.RuntimeException", :message "Failure updating row"}
              {:class-name  "java.sql.SQLException"
               :message     "Database failure\nSELECT FOO, BAR, BAZ\nFROM GNIP\nfailed with ABC123"
               :stack-trace [{:simple-class   "user$jdbc_update"
                              :package        nil
                              :is-clojure?    true
                              :method         "invoke"
                              :name           "user/jdbc-update"
                              :formatted-name "user/jdbc-update"
                              :file           "user.clj"
                              :line           7
                              :class          "user$jdbc_update"
                              :names          '("user" "jdbc-update")}
                             {:simple-class   "user$make_jdbc_update_worker$reify__497"
                              :package        nil
                              :is-clojure?    true
                              :method         "do_work"
                              :name           "user/make-jdbc-update-worker/reify/do-work"
                              :formatted-name "user/make-jdbc-update-worker/reify/do-work"
                              :file           "user.clj"
                              :line           18
                              :class          "user$make_jdbc_update_worker$reify__497"
                              :names          '("user" "make-jdbc-update-worker" "reify" "do-work")}
                             {:simple-class   "user$update_row"
                              :package        nil
                              :is-clojure?    true
                              :method         "invoke"
                              :name           "user/update-row"
                              :formatted-name "user/update-row"
                              :file           "user.clj"
                              :line           23
                              :class          "user$update_row"
                              :names          '("user" "update-row")}
                             {:simple-class   "user$make_exception"
                              :package        nil
                              :is-clojure?    true
                              :method         "invoke"
                              :name           "user/make-exception"
                              :formatted-name "user/make-exception"
                              :file           "user.clj"
                              :line           31
                              :class          "user$make_exception"
                              :names          '("user" "make-exception")}
                             {:simple-class   "user$eval2018"
                              :package        nil
                              :is-clojure?    true
                              :method         "invoke"
                              :name           "user/eval2018"
                              :formatted-name "user/eval2018"
                              :file           "REPL Input"
                              :line           nil
                              :class          "user$eval2018"
                              :names          '("user" "eval2018")}
                             {:simple-class   "Compiler"
                              :package        "clojure.lang"
                              :omitted        true
                              :is-clojure?    false
                              :method         "eval"
                              :name           ""
                              :formatted-name "..."
                              :file           ""
                              :line           nil
                              :class          "clojure.lang.Compiler"
                              :names          []}
                             {:simple-class   "core$eval"
                              :package        "clojure"
                              :is-clojure?    true
                              :method         "invoke"
                              :name           "clojure.core/eval"
                              :formatted-name "clojure.core/eval"
                              :file           "core.clj"
                              :line           2852
                              :class          "clojure.core$eval"
                              :names          '("clojure.core" "eval")}]}]
             (parse "java.lang.RuntimeException: Request handling exception"
                    "\tat user$make_exception.invoke(user.clj:31)"
                    "\tat user$eval2018.invoke(form-init1482095333541107022.clj:1)"
                    "\tat clojure.lang.Compiler.eval(Compiler.java:6619)"
                    "\tat clojure.lang.Compiler.eval(Compiler.java:6582)"
                    "\tat clojure.core$eval.invoke(core.clj:2852)"
                    "\tat clojure.main$repl$read_eval_print__6602$fn__6605.invoke(main.clj:259)"
                    "\tat clojure.main$repl$read_eval_print__6602.invoke(main.clj:259)"
                    "\tat clojure.main$repl$fn__6611$fn__6612.invoke(main.clj:277)"
                    "\tat clojure.main$repl$fn__6611.invoke(main.clj:277)"
                    "\tat clojure.main$repl.doInvoke(main.clj:275)"
                    "\tat clojure.lang.RestFn.invoke(RestFn.java:1523)"
                    "\tat clojure.tools.nrepl.middleware.interruptible_eval$evaluate$fn__1419.invoke(interruptible_eval.clj:72)"
                    "\tat clojure.lang.AFn.applyToHelper(AFn.java:159)"
                    "\tat clojure.lang.AFn.applyTo(AFn.java:151)"
                    "\tat clojure.core$apply.invoke(core.clj:617)"
                    "\tat clojure.core$with_bindings_STAR_.doInvoke(core.clj:1788)"
                    "\tat clojure.lang.RestFn.invoke(RestFn.java:425)"
                    "\tat clojure.tools.nrepl.middleware.interruptible_eval$evaluate.invoke(interruptible_eval.clj:56)"
                    "\tat clojure.tools.nrepl.middleware.interruptible_eval$interruptible_eval$fn__1461$fn__1464.invoke(interruptible_eval.clj:191)"
                    "\tat clojure.tools.nrepl.middleware.interruptible_eval$run_next$fn__1456.invoke(interruptible_eval.clj:159)"
                    "\tat clojure.lang.AFn.run(AFn.java:24)"
                    "\tat java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1142)"
                    "\tat java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:617)"
                    "\tat java.lang.Thread.run(Thread.java:745)"
                    "Caused by: java.lang.RuntimeException: Failure updating row"
                    "\tat user$update_row.invoke(user.clj:23)"
                    "\t... 24 more"
                    "Caused by: java.sql.SQLException: Database failure"
                    "SELECT FOO, BAR, BAZ"
                    "FROM GNIP"
                    "failed with ABC123"
                    "\tat user$jdbc_update.invoke(user.clj:7)"
                    "\tat user$make_jdbc_update_worker$reify__497.do_work(user.clj:18)"
                    "\t... 25 more"))

          (is (= [{:class-name "com.datastax.driver.core.TransportException", :message "/17.76.3.14:9042 Cannot connect"}
                  {:class-name "java.net.ConnectException",
                   :message "Connection refused: /17.76.3.14:9042",
                   :stack-trace [{:simple-class "SocketChannelImpl"
                                  :package "sun.nio.ch"
                                  :is-clojure? false
                                  :method "checkConnect"
                                  :name ""
                                  :formatted-name "sun.nio.ch.SocketChannelImpl.checkConnect"
                                  :file ""
                                  :line nil
                                  :class "sun.nio.ch.SocketChannelImpl"
                                  :names []}
                                 {:simple-class "SocketChannelImpl"
                                  :package "sun.nio.ch"
                                  :is-clojure? false
                                  :method "finishConnect"
                                  :name ""
                                  :formatted-name "sun.nio.ch.SocketChannelImpl.finishConnect"
                                  :file "SocketChannelImpl.java"
                                  :line 717
                                  :class "sun.nio.ch.SocketChannelImpl"
                                  :names []}
                                 {:simple-class "NioClientBoss"
                                  :package "com.datastax.shaded.netty.channel.socket.nio"
                                  :is-clojure? false
                                  :method "connect"
                                  :name ""
                                  :formatted-name "com.datastax.shaded.netty.channel.socket.nio.NioClientBoss.connect"
                                  :file "NioClientBoss.java"
                                  :line 150
                                  :class "com.datastax.shaded.netty.channel.socket.nio.NioClientBoss"
                                  :names []}
                                 {:simple-class "NioClientBoss"
                                  :package "com.datastax.shaded.netty.channel.socket.nio"
                                  :is-clojure? false
                                  :method "processSelectedKeys"
                                  :name ""
                                  :formatted-name "com.datastax.shaded.netty.channel.socket.nio.NioClientBoss.processSelectedKeys"
                                  :file "NioClientBoss.java"
                                  :line 105
                                  :class "com.datastax.shaded.netty.channel.socket.nio.NioClientBoss"
                                  :names []}
                                 {:simple-class "NioClientBoss"
                                  :package "com.datastax.shaded.netty.channel.socket.nio"
                                  :is-clojure? false
                                  :method "process"
                                  :name ""
                                  :formatted-name "com.datastax.shaded.netty.channel.socket.nio.NioClientBoss.process"
                                  :file "NioClientBoss.java"
                                  :line 79
                                  :class "com.datastax.shaded.netty.channel.socket.nio.NioClientBoss"
                                  :names []}
                                 {:simple-class "AbstractNioSelector"
                                  :package "com.datastax.shaded.netty.channel.socket.nio"
                                  :is-clojure? false
                                  :method "run"
                                  :name ""
                                  :formatted-name "com.datastax.shaded.netty.channel.socket.nio.AbstractNioSelector.run"
                                  :file "AbstractNioSelector.java"
                                  :line 318
                                  :class "com.datastax.shaded.netty.channel.socket.nio.AbstractNioSelector"
                                  :names []}
                                 {:simple-class "NioClientBoss"
                                  :package "com.datastax.shaded.netty.channel.socket.nio"
                                  :is-clojure? false
                                  :method "run"
                                  :name ""
                                  :formatted-name "com.datastax.shaded.netty.channel.socket.nio.NioClientBoss.run"
                                  :file "NioClientBoss.java"
                                  :line 42
                                  :class "com.datastax.shaded.netty.channel.socket.nio.NioClientBoss"
                                  :names []}
                                 {:simple-class "ThreadRenamingRunnable"
                                  :package "com.datastax.shaded.netty.util"
                                  :is-clojure? false
                                  :method "run"
                                  :name ""
                                  :formatted-name "com.datastax.shaded.netty.util.ThreadRenamingRunnable.run"
                                  :file "ThreadRenamingRunnable.java"
                                  :line 108
                                  :class "com.datastax.shaded.netty.util.ThreadRenamingRunnable"
                                  :names []}
                                 {:simple-class "DeadLockProofWorker$1"
                                  :package "com.datastax.shaded.netty.util.internal"
                                  :is-clojure? false
                                  :method "run"
                                  :name ""
                                  :formatted-name "com.datastax.shaded.netty.util.internal.DeadLockProofWorker$1.run"
                                  :file "DeadLockProofWorker.java"
                                  :line 42
                                  :class "com.datastax.shaded.netty.util.internal.DeadLockProofWorker$1"
                                  :names []}
                                 {:simple-class "Connection"
                                  :package "com.datastax.driver.core"
                                  :is-clojure? false
                                  :method "<init>"
                                  :name ""
                                  :formatted-name "com.datastax.driver.core.Connection.<init>"
                                  :file "Connection.java"
                                  :line 104
                                  :class "com.datastax.driver.core.Connection"
                                  :names []}
                                 {:simple-class "PooledConnection"
                                  :package "com.datastax.driver.core"
                                  :is-clojure? false
                                  :method "<init>"
                                  :name ""
                                  :formatted-name "com.datastax.driver.core.PooledConnection.<init>"
                                  :file "PooledConnection.java"
                                  :line 32
                                  :class "com.datastax.driver.core.PooledConnection"
                                  :names []}
                                 {:simple-class "Connection$Factory"
                                  :package "com.datastax.driver.core"
                                  :is-clojure? false
                                  :method "open"
                                  :name ""
                                  :formatted-name "com.datastax.driver.core.Connection$Factory.open"
                                  :file "Connection.java"
                                  :line 557
                                  :class "com.datastax.driver.core.Connection$Factory"
                                  :names []}
                                 {:simple-class "DynamicConnectionPool"
                                  :package "com.datastax.driver.core"
                                  :is-clojure? false
                                  :method "<init>"
                                  :name ""
                                  :formatted-name "com.datastax.driver.core.DynamicConnectionPool.<init>"
                                  :file "DynamicConnectionPool.java"
                                  :line 74
                                  :class "com.datastax.driver.core.DynamicConnectionPool"
                                  :names []}
                                 {:simple-class "HostConnectionPool"
                                  :package "com.datastax.driver.core"
                                  :is-clojure? false
                                  :method "newInstance"
                                  :name ""
                                  :formatted-name "com.datastax.driver.core.HostConnectionPool.newInstance"
                                  :file "HostConnectionPool.java"
                                  :line 33
                                  :class "com.datastax.driver.core.HostConnectionPool"
                                  :names []}
                                 {:simple-class "SessionManager$2"
                                  :package "com.datastax.driver.core"
                                  :is-clojure? false
                                  :method "call"
                                  :name ""
                                  :formatted-name "com.datastax.driver.core.SessionManager$2.call"
                                  :file "SessionManager.java"
                                  :line 231
                                  :class "com.datastax.driver.core.SessionManager$2"
                                  :names []}
                                 {:simple-class "SessionManager$2"
                                  :package "com.datastax.driver.core"
                                  :is-clojure? false
                                  :method "call"
                                  :name ""
                                  :formatted-name "com.datastax.driver.core.SessionManager$2.call"
                                  :file "SessionManager.java"
                                  :line 224
                                  :class "com.datastax.driver.core.SessionManager$2"
                                  :names []}
                                 {:simple-class "FutureTask"
                                  :package "java.util.concurrent"
                                  :is-clojure? false
                                  :method "run"
                                  :name ""
                                  :formatted-name "java.util.concurrent.FutureTask.run"
                                  :file "FutureTask.java"
                                  :line 266
                                  :class "java.util.concurrent.FutureTask"
                                  :names []}
                                 {:simple-class "ThreadPoolExecutor"
                                  :package "java.util.concurrent"
                                  :is-clojure? false
                                  :method "runWorker"
                                  :name ""
                                  :formatted-name "java.util.concurrent.ThreadPoolExecutor.runWorker"
                                  :file "ThreadPoolExecutor.java"
                                  :line 1142
                                  :class "java.util.concurrent.ThreadPoolExecutor"
                                  :names []}
                                 {:simple-class "ThreadPoolExecutor$Worker"
                                  :package "java.util.concurrent"
                                  :is-clojure? false
                                  :method "run"
                                  :name ""
                                  :formatted-name "java.util.concurrent.ThreadPoolExecutor$Worker.run"
                                  :file "ThreadPoolExecutor.java"
                                  :line 617
                                  :class "java.util.concurrent.ThreadPoolExecutor$Worker"
                                  :names []}
                                 {:simple-class "Thread"
                                  :package "java.lang"
                                  :is-clojure? false
                                  :method "run"
                                  :name ""
                                  :formatted-name "java.lang.Thread.run"
                                  :file "Thread.java"
                                  :line 745
                                  :class "java.lang.Thread"
                                  :names []}]}]

                 (parse "com.datastax.driver.core.TransportException: /17.76.3.14:9042 Cannot connect"
                        "\tat com.datastax.driver.core.Connection.<init>(Connection.java:104) ~store-service.jar:na"
                        "\tat com.datastax.driver.core.PooledConnection.<init>(PooledConnection.java:32) ~store-service.jar:na"
                        "\tat com.datastax.driver.core.Connection$Factory.open(Connection.java:557) ~store-service.jar:na"
                        "\tat com.datastax.driver.core.DynamicConnectionPool.<init>(DynamicConnectionPool.java:74) ~store-service.jar:na"
                        "\tat com.datastax.driver.core.HostConnectionPool.newInstance(HostConnectionPool.java:33) ~store-service.jar:na"
                        "\tat com.datastax.driver.core.SessionManager$2.call(SessionManager.java:231) store-service.jar:na"
                        "\tat com.datastax.driver.core.SessionManager$2.call(SessionManager.java:224) store-service.jar:na"
                        "\tat java.util.concurrent.FutureTask.run(FutureTask.java:266) na:1.8.0_66"
                        "\tat java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1142) na:1.8.0_66"
                        "\tat java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:617) na:1.8.0_66"
                        "\tat java.lang.Thread.run(Thread.java:745) na:1.8.0_66"
                        "Caused by: java.net.ConnectException: Connection refused: /17.76.3.14:9042"
                        "\tat sun.nio.ch.SocketChannelImpl.checkConnect(Native Method) ~na:1.8.0_66"
                        "\tat sun.nio.ch.SocketChannelImpl.finishConnect(SocketChannelImpl.java:717) ~na:1.8.0_66"
                        "\tat com.datastax.shaded.netty.channel.socket.nio.NioClientBoss.connect(NioClientBoss.java:150) ~store-service.jar:na"
                        "\tat com.datastax.shaded.netty.channel.socket.nio.NioClientBoss.processSelectedKeys(NioClientBoss.java:105) ~store-service.jar:na"
                        "\tat com.datastax.shaded.netty.channel.socket.nio.NioClientBoss.process(NioClientBoss.java:79) ~store-service.jar:na"
                        "\tat com.datastax.shaded.netty.channel.socket.nio.AbstractNioSelector.run(AbstractNioSelector.java:318) ~store-service.jar:na"
                        "\tat com.datastax.shaded.netty.channel.socket.nio.NioClientBoss.run(NioClientBoss.java:42) ~store-service.jar:na"
                        "\tat com.datastax.shaded.netty.util.ThreadRenamingRunnable.run(ThreadRenamingRunnable.java:108) ~store-service.jar:na"
                        "\tat com.datastax.shaded.netty.util.internal.DeadLockProofWorker$1.run(DeadLockProofWorker.java:42) ~store-service.jar:na"
                        "\t... 3 common frames omitted"))))))

(defrecord MyComponent []

  component/Lifecycle
  (start [this] this)
  (stop [this] this))


(deftest component-print-behavior
  (binding [e/*fonts* nil]
    (let [my-component   (map->MyComponent {})
          system         (component/system-map
                           :my-component my-component)
          sys-exception  (format-exception (ex-info "System Exception" {:system system}))
          comp-exception (format-exception (ex-info "Component Exception" {:component my-component}))]

      (reporting {sys-exception (str/split-lines sys-exception)}
                 (is (re-find #"system: #<SystemMap>" sys-exception)))

      (reporting {comp-exception (str/split-lines comp-exception)}
                 (is (re-find #"component: #<Component io.aviso.exception_test.MyComponent>" comp-exception))))))

(deftest write-exceptions-with-nil-data
  (testing "Does not fail with a nil ex-info map key"
    (is (re-find #"nil.*nil"
                 (format-exception (ex-info "Error" {nil nil}))))))
