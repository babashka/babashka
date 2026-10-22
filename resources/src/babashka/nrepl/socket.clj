(ns nrepl.socket
  "Compatibility layer for java.io vs java.nio sockets to allow an
  incremental transition to nio, since the JDK's filesystem sockets
  don't support the java.io socket interface, and we can't use the
  compatibility layer for bidirectional read and write:
  https://bugs.openjdk.java.net/browse/JDK-4509080."
  (:require
   [clojure.java.io :as io]
   [nrepl.misc :refer [log]]
   [nrepl.tls :as tls])
  (:import
   (java.io BufferedInputStream BufferedOutputStream OutputStream)
   (java.net InetSocketAddress ServerSocket Socket URI)
   (java.nio ByteBuffer)
   (java.nio.channels Channels ClosedChannelException ServerSocketChannel SocketChannel)
   (javax.net.ssl SSLServerSocket)))

;;; InetSockets (TCP)

(defn inet-socket
  ([bind port]
   (let [port (or port 0)
         addr (fn [^String bind port] (InetSocketAddress. bind (int port)))
         ;; We fallback to 127.0.0.1 instead of to localhost to avoid
         ;; a dependency on the order of ipv4 and ipv6 records for
         ;; localhost in /etc/hosts
         bind (or bind "127.0.0.1")]
     (doto (ServerSocket.)
       (.setReuseAddress true)
       (.bind (addr bind port)))))
  ([bind port tls-context]
   (let [port (or port 0)
         ;; We fallback to 127.0.0.1 instead of to localhost to avoid
         ;; a dependency on the order of ipv4 and ipv6 records for
         ;; localhost in /etc/hosts
         bind (or bind "127.0.0.1")]
     (tls/server-socket tls-context bind port))))

;; Unix domain sockets

;;;; Compatibility block.
;; Conditionally loads a particular implementation namespace based on
;; availability of classes on classpath.

(defn- throw-no-sockets []
  (let [msg "Support for filesystem sockets requires JDK 17+ or a junixsocket dependency"]
    (log msg)
    (throw (ex-info msg {:nrepl/kind ::no-filesystem-sockets}))))

(def unix-domain-flavor
  (cond
    (resolve 'java.net.UnixDomainSocketAddress) :jdk
    (resolve 'org.newsclub.net.unix.AFUNIXSocketAddress) :junixsocket
    :else nil))

(defn unix-server-socket
  "Returns a server filesystem socket bound to the path based on available
  implementation. Throws ex-info map with {:nrepl/kind ::no-filesystem-sockets}
  if none is available."
  ^ServerSocketChannel [path]
  (case unix-domain-flavor
    :jdk
    ((requiring-resolve 'nrepl.socket.jdk17/unix-server-socket) path)
    :junixsocket
    ((requiring-resolve 'nrepl.socket.junixsocket/unix-server-socket) path)
    ;; else
    (throw-no-sockets)))

(defn unix-client-socket
  "Returns a client filesystem socket bound to the path based on available
  implementation. Throws ex-info map with {:nrepl/kind ::no-filesystem-sockets}
  if none is available."
  ^SocketChannel [path]
  (case unix-domain-flavor
    :jdk
    ((requiring-resolve 'nrepl.socket.jdk17/unix-client-socket) path)
    :junixsocket
    ((requiring-resolve 'nrepl.socket.junixsocket/unix-client-socket) path)
    ;; else
    (throw-no-sockets)))

(defn- get-socket-address-path
  "Given a socket address instance, return its filesystem path as a
  string. Depends on loaded Unix socket implementation."
  [addr]
  (case unix-domain-flavor
    :jdk
    ((requiring-resolve 'nrepl.socket.jdk17/get-socket-address-path) addr)
    :junixsocket
    ((requiring-resolve 'nrepl.socket.junixsocket/get-socket-address-path) addr)
    ;; else
    (throw-no-sockets)))

;;;; Common parts.

(defn as-nrepl-uri
  "Constructs an nREPL URI from a server map and transport scheme.
  Takes a map with :server-socket and :host keys (as in nrepl.server/Server)."
  ^URI [{:keys [host server-socket]} transport-scheme]
  (if (instance? ServerSocketChannel server-socket)
    (URI. (str transport-scheme "+unix")
          (get-socket-address-path
           (.getLocalAddress ^ServerSocketChannel server-socket))
          nil)
    (URI. (str transport-scheme
               (when (instance? SSLServerSocket server-socket)
                 "s"))
          nil
          host
          (.getLocalPort ^ServerSocket server-socket)
          nil nil nil)))

(defprotocol Acceptable
  (accept [s]
    "Accepts a connection on s.  Throws ClosedChannelException if s is
    closed."))

(extend-protocol Acceptable
  ServerSocketChannel
  (accept [s] (.accept s))

  SSLServerSocket
  (accept [s]
    (when (.isClosed s)
      (throw (ClosedChannelException.)))
    (tls/accept s))

  ServerSocket
  (accept [s]
    (when (.isClosed s)
      (throw (ClosedChannelException.)))
    (.accept s)))

(defprotocol Connectable
  (is-connected? [s]
    "Returns true if socket-like thing `s` is currently connected."))

(extend-protocol Connectable
  SocketChannel
  (is-connected? [s] (.isConnected s))

  Socket
  (is-connected? [s] (.isConnected s)))

;; We have to handle this ourselves for NIO because unfortunately read and write
;; hang if we use both Channels/newInputStream and Channels/newOutputStream.
;; Read and write deadlock on a shared channel input/output stream lock
;; (cf. https://bugs.openjdk.java.net/browse/JDK-4509080).  Verified that this
;; still happens (via thread dump when hung) with jdk 17.

(defprotocol Writable
  ;; Underscores were added to satisfy clj-kondo
  (write
    [w byte-array]
    [w byte-array offset length]
    "Writes the given bytes to the output as per OutputStream write."))

(extend-protocol Writable
  OutputStream
  (write
    ([s byte-array] (.write ^OutputStream s ^"[B" byte-array))
    ([s byte-array offset length]
     (.write ^OutputStream s byte-array offset length))))

#_(defrecord BufferedOutputChannel
           [^SocketChannel channel ^ByteBuffer buffer]

  java.io.Flushable
  (flush [_this] ;; Underscore was added to satisfy clj-kondo
    (.flip buffer)
    (.write channel buffer)
    (.clear buffer))

  Writable
  (write [this byte-array]
    (.write this byte-array 0 (count byte-array)))
  (write [this byte-array offset length]
    (if (> length (.capacity buffer))
      (do
        (.flush this)
        (.write channel (ByteBuffer/wrap byte-array offset length)))
      (do
        (when (> length (.remaining buffer))
          (.flush this))
        (.put buffer byte-array offset length))))) ;; BB-PATCH sci defrecord cannot implement a Java interface, reify can
(defn buffered-output-channel [^SocketChannel channel bytes]
  (assert (.isBlocking channel))
  (let [^ByteBuffer buffer (ByteBuffer/allocate bytes)
        flush! (fn []
                 (.flip buffer)
                 (.write channel buffer)
                 (.clear buffer))]
    (reify
      java.io.Flushable
      (flush [_this] (flush!))
      Writable
      (write [this byte-array]
        (write this byte-array 0 (count byte-array)))
      (write [_this byte-array offset length]
        (if (> length (.capacity buffer))
          (do (flush!)
              (.write channel (ByteBuffer/wrap byte-array offset length)))
          (do (when (> length (.remaining buffer))
                (flush!))
              (.put buffer byte-array offset length)))))))

#_(defn buffered-output-channel [^SocketChannel channel bytes]
  (assert (.isBlocking channel))
  (->BufferedOutputChannel channel (ByteBuffer/allocate bytes))) ;; BB-PATCH replaced above


(defprotocol AsBufferedInputStreamSubset
  (buffered-input [x]
    "Returns a buffered stream (subset of BufferedInputStream) reading from x."))

(extend-protocol AsBufferedInputStreamSubset
  ;; Use the Channels stream for input but not output to avoid the deadlock
  SocketChannel (buffered-input [s] (-> s Channels/newInputStream io/input-stream))
  Socket (buffered-input [s] (io/input-stream s))
  BufferedInputStream (buffered-input [s] s))

(defprotocol AsBufferedOutputStreamSubset
  (buffered-output [x]
    "Returns a buffered stream (subset of BufferedOutputStream) reading from x."))

(extend-protocol AsBufferedOutputStreamSubset
  ;; Use the Channels stream for input but not output to avoid the deadlock
  SocketChannel (buffered-output [s] (buffered-output-channel s 8192))
  Socket (buffered-output [s] (io/output-stream s))
  BufferedOutputStream (buffered-output [s] s))
