(ns nrepl.socket
  "Compatibility layer for java.io vs java.nio sockets to allow an
  incremental transition to nio, since the JDK's filesystem sockets
  don't support the java.io socket interface, and we can't use the
  compatibility layer for bidirectional read and write:
  https://bugs.openjdk.java.net/browse/JDK-4509080."
  (:require
   [clojure.java.io :as io]
   [nrepl.misc :refer [log]]
   [nrepl.socket.dynamic :refer [get-path]]
   [nrepl.tls :as tls])
  (:import
   (java.io BufferedInputStream BufferedOutputStream File OutputStream)
   (java.net InetSocketAddress ProtocolFamily ServerSocket Socket SocketAddress
             StandardProtocolFamily URI)
   (java.nio ByteBuffer)
   (java.nio.file Path)
   (java.nio.channels Channels ClosedChannelException NetworkChannel
                      ServerSocketChannel SocketChannel)
   (javax.net.ssl SSLServerSocket)))

(defmacro find-class [full-path]
  `(try
     (Class/forName (name ~full-path))
     (catch ClassNotFoundException ex#
       nil)))

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

(def ^Class junixsocket-address-class
  (find-class 'org.newsclub.net.unix.AFUNIXSocketAddress))

(def ^Class junixsocket-server-class
  (find-class 'org.newsclub.net.unix.AFUNIXServerSocket))

(def ^Class junixsocket-class
  (find-class 'org.newsclub.net.unix.AFUNIXSocket))

(def ^Class jdk-unix-address-class
  (find-class 'java.net.UnixDomainSocketAddress))

(def unix-domain-flavor
  (cond
    jdk-unix-address-class :jdk
    (and junixsocket-address-class junixsocket-server-class) :junixsocket
    :else nil))

(def jdk-unix-address-of
  (when (= :jdk unix-domain-flavor)
    (let [addr-of (.getDeclaredMethod jdk-unix-address-class "of"
                                      (into-array Class [String]))]
      (fn [path] (.invoke addr-of nil (into-array String [path]))))))

(def junix-address-of
  (when (= :junixsocket unix-domain-flavor)
    (try (let [addr-of (.getDeclaredMethod junixsocket-address-class "of"
                                           (into-array Class [File]))]
           (fn [path] (.invoke addr-of nil (into-array File [(File. ^String path)]))))
         (catch NoSuchMethodException _))))

(def ^:private error-msg
  "Support for filesystem sockets requires JDK 17+ or a junixsocket dependency")

(defn unix-socket-address
  "Returns a filesystem socket address for the given path string."
  [^String path]
  (case unix-domain-flavor
    :jdk (jdk-unix-address-of path)
    :junixsocket (junix-address-of path)
    (do
      (log error-msg)
      (throw (ex-info error-msg {:nrepl/kind ::no-filesystem-sockets})))))

(def jdk-unix-server-socket
  ;; Dynamic because one argument open doesn't exist until jvm 15, nor UNIX
  ;; until jvm 16.
  (when (= :jdk unix-domain-flavor)
    (let [protocol (-> (.getDeclaredField StandardProtocolFamily "UNIX")
                       (.get StandardProtocolFamily))
          protocol (into-array ProtocolFamily [protocol])
          open (.getDeclaredMethod ServerSocketChannel "open"
                                   (into-array Class [ProtocolFamily]))]
      #(.invoke open nil protocol))))

(def jdk-unix-socket
  ;; Dynamic because one argument open doesn't exist until jvm 15, nor UNIX
  ;; until jvm 16.
  (when (= :jdk unix-domain-flavor)
    (let [protocol (-> (.getDeclaredField StandardProtocolFamily "UNIX")
                       (.get StandardProtocolFamily))
          protocol (into-array ProtocolFamily [protocol])
          open (.getDeclaredMethod SocketChannel "open"
                                   (into-array Class [ProtocolFamily]))]
      #(.invoke open nil protocol))))

(def junix-server-socket
  (when (= :junixsocket unix-domain-flavor)
    (let [make (.getDeclaredMethod junixsocket-server-class "newInstance" nil)]
      #(.invoke make nil nil))))

(def junix-socket
  (when (= :junixsocket unix-domain-flavor)
    (let [make (.getDeclaredMethod junixsocket-class "newInstance" nil)]
      #(.invoke make nil nil))))

(defn unix-server-socket
  "Returns a filesystem socket bound to the path if the JDK is version
  16 or newer or if com.kohlschutter.junixsocket/junixsocket-core can
  be loaded dynamically.  Otherwise throws the ex-info map
  {:nrepl/kind ::no-filesystem-sockets}."
  [^String path]
  (let [^SocketAddress addr (unix-socket-address path)]
    (case unix-domain-flavor
      :jdk
      (let [sock (jdk-unix-server-socket)]
        (.bind ^ServerSocketChannel sock addr)
        (let [^Path path (get-path addr)]
          (-> path .toFile .deleteOnExit))
        sock)

      :junixsocket
      (let [sock (junix-server-socket)]
        (.bind ^ServerSocket sock addr)
        (let [^String path (get-path addr)]
          (-> path File. .deleteOnExit))
        sock)

      (do
        (log error-msg)
        (throw (ex-info error-msg {:nrepl/kind ::no-filesystem-sockets}))))))

(defn unix-client-socket
  "Returns a filesystem socket bound to the path if the JDK is version
  16 or newer or if com.kohlschutter.junixsocket/junixsocket-core can
  be loaded dynamically.  Otherwise throws the ex-info map
  {:nrepl/kind ::no-filesystem-sockets}."
  [^String path]
  (let [^SocketAddress addr (unix-socket-address path)]
    (case unix-domain-flavor
      :jdk
      (let [sock (jdk-unix-socket)]
        (.connect ^SocketChannel sock addr)
        sock)

      :junixsocket
      (let [sock (junix-socket)]
        (.connect ^Socket sock addr)
        sock)

      (do
        (log error-msg)
        (throw (ex-info error-msg {:nrepl/kind ::no-filesystem-sockets}))))))

(defn as-nrepl-uri
  "Constructs an nREPL URI from a server map and transport scheme.
  Takes a map with :server-socket and :host keys (as in nrepl.server/Server)."
  ^URI [{:keys [host server-socket]} transport-scheme]
  (cond
    (instance? ServerSocketChannel server-socket)
    (URI. (str transport-scheme "+unix")
          (let [^Path path (get-path (.getLocalAddress ^NetworkChannel server-socket))]
            (-> path .toAbsolutePath str))
          nil)

    (and junixsocket-server-class (instance? junixsocket-server-class server-socket))
    (URI. (str transport-scheme "+unix")
          (get-path (.getLocalSocketAddress ^ServerSocket server-socket))
          nil)

    :else
    (URI. (str transport-scheme
               (when (instance? SSLServerSocket server-socket)
                 "s"))
          nil
          host
          (.getLocalPort ^ServerSocket server-socket)
          nil nil nil))) ;; fragment

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
;; still happens (via thread dump when hung) with jdk 16.

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
