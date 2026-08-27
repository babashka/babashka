;;   Modified / stripped version of clojure.core.server for use with babashka on
;;   GraalVM.
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
  (:require [babashka.impl.clojure.core :as core]
            [babashka.impl.clojure.main :as m]
            [babashka.impl.common :refer [debug]]
            [sci.core :as sci]
            [sci.impl.parser :as p]
            [sci.impl.utils :as utils])
  (:import
   [clojure.lang LineNumberingPushbackReader]
   [java.io BufferedWriter InputStreamReader OutputStreamWriter]
   [java.net BindException InetAddress Socket ServerSocket SocketException
    StandardProtocolFamily UnixDomainSocketAddress]
   [java.nio.channels Channels ClosedChannelException ServerSocketChannel SocketChannel]
   [java.nio.file Files LinkOption Path Paths]
   [java.util.concurrent.locks ReentrantLock]))

(set! *warn-on-reflection* true)

(def ^:dynamic *session* nil)

;; lock protects servers
(defonce ^:private lock (ReentrantLock.))
(defonce ^:private servers {})

(defmacro ^:private with-lock
  [lock-expr & body]
  `(let [lockee# ~(with-meta lock-expr {:tag 'java.util.concurrent.locks.ReentrantLock})]
     (.lock lockee#)
     (try
       ~@body
       (finally
         (.unlock lockee#)))))

(defmacro ^:private thread
  [^String name daemon & body]
  `(doto (Thread. (fn [] ~@body) ~name)
     (.setDaemon ~daemon)
     (.start)))

(defn- resolve-fn [ctx valf]
  (if (symbol? valf)
    (let [fully-qualified (p/fully-qualify ctx valf)]
      (or (some-> ctx :env deref :namespaces
                  (get (symbol (namespace fully-qualified)))
                  (get (symbol (name fully-qualified))))
          (throw (Exception. (str "can't resolve: " valf)))))
    valf))

(defprotocol Listener
  (accept-conn [listener] "Blocks until a client connects; returns the connection.")
  (listener-closed? [listener])
  (close-listener! [listener])
  (describe [listener] "Human-readable address, for the startup banner."))

(defprotocol Connection
  (conn-in [conn])
  (conn-out [conn])
  (close-conn! [conn]))

(extend-protocol Connection
  Socket
  (conn-in [conn] (.getInputStream conn))
  (conn-out [conn] (.getOutputStream conn))
  (close-conn! [conn] (.close conn))
  SocketChannel
  (conn-in [conn] (Channels/newInputStream conn))
  (conn-out [conn] (Channels/newOutputStream conn))
  (close-conn! [conn] (.close conn)))

(extend-protocol Listener
  ServerSocket
  (accept-conn [listener] (.accept listener))
  (listener-closed? [listener] (.isClosed listener))
  (close-listener! [listener] (.close listener))
  (describe [listener]
    (str (.getHostAddress (.getInetAddress listener)) ":" (.getLocalPort listener))))

(defrecord UnixListener [^ServerSocketChannel channel ^Path path]
  Listener
  (accept-conn [_] (.accept channel))
  (listener-closed? [_] (not (.isOpen channel)))
  (close-listener! [_]
    (.close channel)
    (Files/deleteIfExists path))
  (describe [_] (str path)))

(defn- bind-unix-listener ^ServerSocketChannel [^Path path]
  (let [address (UnixDomainSocketAddress/of path)
        channel (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
    (try (.bind channel address)
         (catch BindException e
           ;; A file already exists at path. If nothing accepts connections on
           ;; it, it was left behind by a dead process: remove it and rebind.
           (let [stale? (try (.close (SocketChannel/open address)) false
                             (catch java.io.IOException _ true))]
             (if stale?
               (do (Files/deleteIfExists path)
                   (.bind channel address))
               (do (.close channel)
                   (throw (ex-info (str "A server is already listening on " path)
                                   {:socket (str path)} e))))))
         (catch SocketException e
           ;; e.g. ENOENT: the parent directory of path does not exist
           (.close channel)
           (let [parent (.getParent path)
                 msg (if (and parent (not (Files/exists parent (into-array LinkOption []))))
                       (str "Cannot start server at " path
                            ": parent directory " parent " does not exist")
                       (str "Cannot start server at " path ": " (.getMessage e)))]
             (throw (ex-info msg {:socket (str path)} e)))))
    channel))

(defn- unix-listener [{:keys [socket]}]
  (let [path (.toAbsolutePath (Paths/get ^String socket (into-array String [])))
        channel (bind-unix-listener path)]
    ;; SIGINT/exit is the normal way these servers stop; clean up the file.
    (.deleteOnExit (.toFile path))
    (->UnixListener channel path)))

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
  [ctx conn client-id in out err accept args]
  (let [accept (resolve-fn ctx accept)]
    (try
      (binding [*session* {:server name :client client-id}]
        (sci/with-bindings {sci/in in
                            sci/out out
                            sci/err err
                            sci/ns (sci/create-ns 'user nil)}
          (with-lock lock
            (alter-var-root #'servers assoc-in [name :sessions client-id] {}))
          (apply accept args)))
      (catch SocketException _disconnect)
      (catch ClosedChannelException _disconnect)
      (finally
        (with-lock lock
          (alter-var-root #'servers update-in [name :sessions] dissoc client-id))
        (close-conn! conn)))))

(defn start-server
  "Start a socket server given the specified opts:
    :address Host or address, string, defaults to loopback address
    :port Port, integer, required unless :socket is given
    :socket Path to a UNIX domain socket file, mutually exclusive with :address and :port
    :name Name, required
    :accept Namespaced symbol of the accept function to invoke, required
    :args Vector of args to pass to accept function
    :bind-err Bind *err* to socket out stream?, defaults to true
    :server-daemon Is server thread a daemon?, defaults to true
    :client-daemon Are client threads daemons?, defaults to true
   Returns the server listener: a ServerSocket, or a UNIX domain socket
   listener when :socket was given."
  [ctx opts]
  (let [{:keys [address port socket name accept args bind-err server-daemon client-daemon]
         :or {bind-err true
              server-daemon true
              client-daemon true}} opts
        socket (if socket
                 (unix-listener opts)
                 ;; nil address returns loopback
                 (ServerSocket. port 0 (InetAddress/getByName address)))]
    (with-lock lock
      (alter-var-root #'servers assoc name {:name name, :socket socket, :sessions {}}))
    (thread
      (str "Clojure Server " name) server-daemon
      (try
        (loop [client-counter 1]
          (when (not (listener-closed? socket))
            (try
              (let [conn (accept-conn socket)
                    in (LineNumberingPushbackReader. (InputStreamReader. (conn-in conn)))
                    out (BufferedWriter. (OutputStreamWriter. (conn-out conn)))
                    client-id (str client-counter)]
                (thread
                  (str "Clojure Connection " name " " client-id) client-daemon
                  (accept-connection ctx conn client-id in out (if bind-err out *err*) accept args)))
              (catch SocketException _disconnect)
              (catch ClosedChannelException _disconnect))
            (recur (inc client-counter))))
        (finally
          (with-lock lock
            (alter-var-root #'servers dissoc name)))))
    socket))

(defn stop-server
  "Stop server with name or use the server-name from *session* if none supplied.
  Returns true if server stopped successfully, nil if not found, or throws if
  there is an error closing the socket."
  ([]
   (stop-server (:server *session*)))
  ([name]
   (with-lock lock
     (let [listener (get-in servers [name :socket])]
       (when listener
         (alter-var-root #'servers dissoc name)
         (close-listener! listener)
         true)))))

(defn stop-servers
  "Stop all servers ignores all errors, and returns nil."
  []
  (with-lock lock
    (doseq [name (keys servers)]
      (future (stop-server name)))))

(defn- ex->data
  [ex phase]
  (let [ex (assoc (Throwable->map ex) :phase phase)
        ex (if (not @debug)
             (update ex :trace #(vec (take 100 %)))
             ex)]
    ex))

(defn prepl
  "a REPL with structured output (for programs)
  reads forms to eval from in-reader (a LineNumberingPushbackReader)
  Closing the input or passing the form :repl/quit will cause it to return
  Calls out-fn with data, one of:
  {:tag :ret
   :val val ;;eval result
   :ns ns-name-string
   :ms long ;;eval time in milliseconds
   :form string ;;iff successfully read
   :clojure.error/phase (:execution et al per clojure.main/ex-triage) ;;iff error occurred
  }
  {:tag :out
   :val string} ;chars from during-eval *out*
  {:tag :err
   :val string} ;chars from during-eval *err*
  {:tag :tap
   :val val} ;values from tap>
  You might get more than one :out or :err per eval, but exactly one :ret
  tap output can happen at any time (i.e. between evals)
  If during eval an attempt is made to read *in* it will read from in-reader unless :stdin is supplied
  Alpha, subject to change."
  {:added "1.10"}
  [ctx in-reader out-fn & {:keys [stdin]}]
  (let [EOF (Object.)
        tapfn #(out-fn {:tag :tap :val %1})]
    (m/with-bindings
      (sci/with-bindings {sci/in (or stdin in-reader)
                          sci/out (PrintWriter-on #(out-fn {:tag :out :val %1}) nil)
                          sci/err (PrintWriter-on #(out-fn {:tag :err :val %1}) nil)
                          sci/ns (sci/create-ns 'user nil)
                          sci/print-length @sci/print-length
                          sci/print-level @sci/print-level
                          sci/print-meta @sci/print-meta
                          sci/*1 nil
                          sci/*2 nil
                          sci/*3 nil
                          sci/*e nil}
        (try
          ;; babashka uses Clojure's global tap system so this should be ok
          (add-tap tapfn)
          (loop []
            (when (try
                    (let [[form s] (core/read+string ctx in-reader false EOF)]
                      (try
                        (when-not (identical? form EOF)
                          (let [start (System/nanoTime)
                                ret (sci/with-bindings {sci/*1 *1
                                                        sci/*2 *2
                                                        sci/*3 *3
                                                        sci/*e *e}
                                      (sci/eval-form ctx form))
                                ms (quot (- (System/nanoTime) start) 1000000)]
                            (when-not (= :repl/quit ret)
                              (set! *3 *2)
                              (set! *2 *1)
                              (set! *1 ret)
                              (out-fn {:tag :ret
                                       :val (if (instance? Throwable ret)
                                              (Throwable->map ret)
                                              ret)
                                       :ns (str (utils/current-ns-name))
                                       :ms ms
                                       :form s})
                              true)))
                        (catch Throwable ex
                          (set! *e ex)
                          (out-fn {:tag :ret :val (ex->data ex (or (-> ex ex-data :clojure.error/phase) :execution))
                                   :ns (str (.name *ns*)) :form s
                                   :exception true})
                          true)))
                    (catch Throwable ex
                      (set! *e ex)
                      (out-fn {:tag :ret :val (ex->data ex :read-source)
                               :ns (str (.name *ns*))
                               :exception true})
                      true))
              (recur)))
          (finally
            (remove-tap tapfn)))))))

(defn io-prepl
  "prepl bound to *in* and *out*, suitable for use with e.g. server/repl (socket-repl).
  :ret and :tap vals will be processed by valf, a fn of one argument
  or a symbol naming same (default pr-str)
  Alpha, subject to change."
  {:added "1.10"}
  [ctx & {:keys [valf] :or {valf pr-str}}]
  (let [valf (resolve-fn ctx valf)
        out @sci/out
        lock (Object.)]
    (prepl ctx @sci/in
           (fn [m]
             (binding [*out* out *flush-on-newline* true, *print-readably* true]
               ;; we're binding *out* to the out, which was the original
               ;; sci/out, because we're using Clojure's regular prn below
               (locking lock
                 (prn (if (#{:ret :tap} (:tag m))
                        (try
                          (assoc m :val (valf (:val m)))
                          (catch Throwable ex
                            (assoc m :val (ex->data ex :print-eval-result)
                                   :exception true)))
                        m))))))))
