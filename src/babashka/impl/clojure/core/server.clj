;;  Modified / stripped version of clojure.core.server for use with babashka on
;;  GraalVM.
;;
;;   Copyright (c) Rich Hickey. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:doc "Socket server support"
      :author "Alex Miller"
      :no-doc true}
    babashka.impl.clojure.core.server
  (:refer-clojure :exclude [locking])
  (:require [sci.core :as sci]
            [sci.impl.vars :as vars])
  (:import
   [clojure.lang LineNumberingPushbackReader]
   [java.net InetAddress Socket ServerSocket SocketException]
   [java.io BufferedWriter InputStreamReader OutputStreamWriter]))

(set! *warn-on-reflection* true)

(def server (atom nil))

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
  [^Socket conn client-id in out err accept args]
  (try
    (sci/with-bindings {sci/in in
                        sci/out out
                        sci/err err
                        vars/current-ns (vars/->SciNamespace 'user)}
      (swap! server assoc-in [:sessions client-id] {})
      (apply accept args))
    (catch SocketException _disconnect)
    (finally
      (swap! server update-in [:sessions] dissoc client-id)
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
    (reset! server {:name name, :socket socket, :sessions {}})
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
                  (accept-connection conn client-id in out (if bind-err out *err*) accept args)))
              (catch SocketException _disconnect))
            (recur (inc client-counter))))
        (finally
          (reset! server nil))))
    socket))

(defn stop-server
  "Stop server with name or use the server-name from *session* if none supplied.
  Returns true if server stopped successfully, nil if not found, or throws if
  there is an error closing the socket."
  []
  (when-let [s @server]
    (when-let [server-socket ^ServerSocket (get s :socket)]
      (.close server-socket)))
  (reset! server nil))
