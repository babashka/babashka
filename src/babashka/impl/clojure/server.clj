                                        ;   Modified / lite version of core.server.clj for use with babashka on GraalVM.

                                        ;   Copyright (c) Rich Hickey. All rights reserved.
                                        ;   The use and distribution terms for this software are covered by the
                                        ;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
                                        ;   which can be found in the file epl-v10.html at the root of this distribution.
                                        ;   By using this software in any fashion, you are agreeing to be bound by
                                        ;   the terms of this license.
                                        ;   You must not remove this notice, or any other, from this software.

(ns ^{:doc "Socket server support"
      :author "Alex Miller"}
    babashka.impl.clojure.server
  (:refer-clojure :exclude [locking])
  (:import
   [clojure.lang LineNumberingPushbackReader]
   [java.net InetAddress Socket ServerSocket SocketException]
   [java.io Reader Writer PrintWriter BufferedWriter BufferedReader InputStreamReader OutputStreamWriter]
   [java.util.concurrent.locks ReentrantLock]
   [babashka.impl LockFix]))

(set! *warn-on-reflection* true)

#_(defmacro locking ;; patched version of clojure.core/locking to workaround GraalVM unbalanced monitor issue
  "Executes exprs in an implicit do, while holding the monitor of x.
  Will release the monitor of x in all circumstances."
  {:added "1.0"}
  [x & body]
  `(let [lockee# ~x]
     (LockFix/lock lockee# (^{:once true} fn* [] ~@body))))

(def ^:dynamic *session* nil)

(def ^:private servers (atom {}))

(defmacro ^:private thread
  [^String name daemon & body]
  `(doto (Thread. (fn [] ~@body) ~name)
     (.setDaemon ~daemon)
     (.start)))

(defn- accept-connection
  "Start accept function, to be invoked on a client thread, given:
    conn - client socket
    name - server name
    client-id - client identifier
    in - in stream
    out - out stream
    err - err stream
    accept - accept fn symbol to invoke
    args - to pass to accept-fn"
  [^Socket conn name client-id in out err accept args]
  (try
    (binding [*in* in
              *out* out
              *err* err
              *session* {:server name :client client-id}]
      (swap! servers assoc-in [name :sessions client-id] {})
      (apply accept args))
    (catch SocketException _disconnect)
    (finally
      (swap! servers update-in [name :sessions] dissoc client-id)
      (.close conn))))

(defn start-server
  "Start a socket server given the specified opts:
    :address Host or address, string, defaults to loopback address
    :port Port, integer, required
    :name Name, required
    :accept Namespaced symbol of the accept function to invoke, required
    :args Vector of args to pass to accept function
    :bind-err Bind *err* to socket out stream?, defaults to true
    :server-daemon Is server thread a daemon?, defaults to true
    :client-daemon Are client threads daemons?, defaults to true
   Returns server socket."
  [opts]
  (let [{:keys [address port name accept args bind-err server-daemon client-daemon]
         :or {bind-err true
              server-daemon true
              client-daemon true}} opts
        address (InetAddress/getByName address)  ;; nil returns loopback
        socket (ServerSocket. port 0 address)]
    (swap! servers assoc name {:name name, :socket socket, :sessions {}})
    (thread
      (str "Clojure Server " name) server-daemon
      (try
        (loop [client-counter 1]
          (when (not (.isClosed socket))
            (try
              (let [conn (.accept socket)
                    in (LineNumberingPushbackReader. (InputStreamReader. (.getInputStream conn)))
                    out (BufferedWriter. (OutputStreamWriter. (.getOutputStream conn)))
                    client-id (str client-counter)]
                (thread
                  (str "Clojure Connection " name " " client-id) client-daemon
                  (accept-connection conn name client-id in out (if bind-err out *err*) accept args)))
              (catch SocketException _disconnect))
            (recur (inc client-counter))))
        (finally
          (swap! servers dissoc name))))
    socket))

(defn stop-server
  "Stop server with name or use the server-name from *session* if none supplied.
  Returns true if server stopped successfully, nil if not found, or throws if
  there is an error closing the socket."
  ([]
   (stop-server (:server *session*)))
  ([name]
   (swap! servers
          (fn [servers]
            (if-let [server-socket ^ServerSocket (get-in servers [name :socket])]
              (do (.close server-socket)
                  (dissoc servers name))
              servers)))))
