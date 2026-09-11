(ns nrepl.socket.dynamic
  "Socket-related code that depends on classes that are only known at
  run time, not compile time.  This just allows us to isolate
  reflections we can't avoid, so that we can easily ask eastwood to
  ignore them.  This namespace should only be needed until JDK 16+ can
  be assumed.")

(set! *warn-on-reflection* false)

;; SocketAddress doesn't have .getPath until JDK 16, and we can't refer to
;; AFUnixSocketAddress unconditionally in the junixsocket cases.  Also note that
;; the former returns a Path, and the latter returns a string.

(defn get-path
  "Return the filesystem path from a Unix domain socket address.
  Works with both JDK 16+ UnixDomainSocketAddress and junixsocket's
  AFUNIXSocketAddress."
  [addr]
  (.getPath addr))
