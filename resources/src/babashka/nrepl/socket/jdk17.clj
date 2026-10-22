(ns nrepl.socket.jdk17
  "Should only be required by JDK17 and above."
  (:import
   (java.net StandardProtocolFamily UnixDomainSocketAddress)
   (java.nio.channels ServerSocketChannel SocketChannel)))

(defn unix-server-socket
  "Return a server filesystem socket bound to the given path."
  [^String path]
  (let [addr (UnixDomainSocketAddress/of path)
        socket-chan (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
    (.bind socket-chan addr)
    (-> addr .getPath .toFile .deleteOnExit)
    socket-chan))

(defn unix-client-socket
  "Returns a client filesystem socket bound to the given path."
  [^String path]
  (let [socket-chan (SocketChannel/open StandardProtocolFamily/UNIX)]
    (.connect socket-chan (UnixDomainSocketAddress/of path))
    socket-chan))

(defn get-socket-address-path
  "Given a socket address instance, return its filesystem path as a string."
  [^UnixDomainSocketAddress socket-addr]
  (-> socket-addr .getPath .toAbsolutePath str))
