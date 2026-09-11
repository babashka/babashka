(ns nrepl.tls
  "Stand-in for nREPL's nrepl.tls: babashka's nREPL server does not do TLS.")

(defn- unsupported []
  (throw (ex-info "TLS is not supported by babashka's nREPL server" {})))

(defn ssl-context-or-throw [_tls-keys-str _tls-keys-file]
  (unsupported))

(defn server-socket [_tls-context _bind _port]
  (unsupported))

(defn accept [_server-socket]
  (unsupported))

(defn socket [_tls-context _host _port _timeout]
  (unsupported))
