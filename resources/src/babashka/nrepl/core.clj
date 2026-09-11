(ns nrepl.core
  "High level nREPL client support."
  {:author "Chas Emerick"}
  (:require
   [nrepl.misc :refer [take-until uuid]]
   [nrepl.socket :as socket]
   [nrepl.tls :as tls]
   [nrepl.transport :as transport]
   [nrepl.version :as version]))

(defn response-seq
  "Returns a lazy seq of messages received via the given Transport.
   Called with no further arguments, will block waiting for each message.
   The seq will end only when the underlying Transport is closed (i.e.
   returns nil from `recv`) or if a message takes longer than `timeout`
   millis to arrive."
  ([transport] (response-seq transport Long/MAX_VALUE))
  ([transport timeout]
   (take-while identity (repeatedly #(transport/recv transport timeout)))))

(defn client
  "Returns a fn of zero and one argument, both of which return the current head of a single
   response-seq being read off of the given client-side transport.  The one-arg arity will
   send a given message on the transport before returning the seq.

   Most REPL interactions are best performed via `message` and `client-session` on top of
   a client fn returned from this fn."
  [transport response-timeout]
  (let [latest-head (atom nil)
        ;; We don't use `response-seq` here but make a custom transport-pulling
        ;; lazy seq that updates `latest-head` to always point to the yet
        ;; unrealized part of the message sequence.
        resp-seq (fn resp-seq []
                   (lazy-seq
                    (if-some [resp (transport/recv transport response-timeout)]
                      (cons resp (reset! latest-head (resp-seq)))
                      (reset! latest-head nil))))]
    ^{::transport transport ::timeout response-timeout}
    (fn this
      ([] (or @latest-head (reset! latest-head (resp-seq))))
      ([msg]
       (transport/send transport msg)
       (this)))))

(defn- delimited-transport-seq
  "Returns a function of one argument that performs described below.
   The following \"message\" is the argument of the function returned by this function.

    - Merge delimited-slots to the message
    - Sends a message via client
    - Filter only items related to the delimited-slots of client's response seq
    - Returns head of the seq that will terminate
      upon receipt of a :status, when :status is an element of termination-statuses"
  [klient termination-statuses delimited-slots]
  (let [delimited-keys (keys delimited-slots)
        termination-statuses (set termination-statuses)]
    (with-meta
      (fn [msg]
        (->> (klient (merge msg delimited-slots))
             (filter #(= delimited-slots (select-keys % delimited-keys)))
             (take-until #(some termination-statuses (:status %)))))
      (-> (meta klient)
          (update ::termination-statuses (fnil into #{}) termination-statuses)
          (update ::taking-until merge delimited-slots)))))

(defn message
  "Sends a message via [client] with a fixed message :id added to it
   by `delimited-transport-seq`.
   Returns the head of the client's response seq, filtered to include only
   messages related to the message :id that will terminate upon receipt of a
   \"done\" :status."
  [client {:keys [id] :as msg :or {id (uuid)}}]
  (let [f (delimited-transport-seq client #{"done" :done} {:id id})]
    (f msg)))

(defn new-session
  "Provokes the creation and retention of a new session, optionally as a clone
   of an existing retained session, the id of which must be provided as a :clone
   kwarg.  Returns the new session's id."
  [client & {:keys [clone]}]
  (let [resp (first (message client (merge {:op "clone"}
                                           (when clone
                                             (select-keys clone [:client-name :client-version :session])))))]
    (or (:new-session resp)
        (throw (IllegalStateException.
                (str "Could not open new session; :clone response: " resp))))))

(defn client-session
  "Returns a function of one argument.  Accepts a message that is sent via the
   client provided with a fixed :session id added to it.  Returns the
   head of the client's response seq, filtered to include only
   messages related to the :session id that will terminate when the session is
   closed."
  [client & {:keys [session clone]}]
  (let [session (or session (apply new-session client (when clone [:clone clone])))]
    (delimited-transport-seq client #{"session-closed" :session-closed} {:session session})))

(defn combine-responses
  "Combines the provided seq of response messages into a single response map.

   Certain message slots are combined in special ways:

     - only the last :ns is retained
     - :value is accumulated into an ordered collection
     - :status and :session are accumulated into a set
     - string values (associated with e.g. :out and :err) are concatenated"
  [responses]
  (reduce
   (fn [m [k v]]
     (case k
       (:id :ns) (assoc m k v)
       :value (update-in m [k] (fnil conj []) v)
       :status (update-in m [k] (fnil into #{}) v)
       :session (update-in m [k] (fnil conj #{}) v)
       (if (string? v)
         (update-in m [k] #(str % v))
         (assoc m k v))))
   {} (apply concat responses)))

(defn code*
  "Returns a single string containing the pr-str'd representations
   of the given expressions."
  [& expressions]
  (apply str (map pr-str expressions)))

(defmacro code
  "Expands into a string consisting of the macro's body's forms
   (literally, no interpolation/quasiquoting of locals or other
   references), suitable for use in an `\"eval\"` message, e.g.:

   {:op \"eval\", :code (code (+ 1 1) (slurp \"foo.txt\"))}"
  [& body]
  (apply code* body))

(defn read-response-value
  "Returns the provided response message, replacing its :value string with
   the result of (read)ing it.  Returns the message unchanged if the :value
   slot is empty or not a string."
  [{:keys [value] :as msg}]
  (if-not (string? value)
    msg
    (try
      (assoc msg :value (read-string value))
      (catch Exception e
        (throw (IllegalStateException. (str "Could not read response value: " value) e))))))

(defn response-values
  "Given a seq of responses (as from response-seq or returned from any function returned
   by client or client-session), returns a seq of values read from :value slots found
   therein."
  [responses]
  (->> responses
       (map read-response-value)
       combine-responses
       :value))

(defn- tls-connect
  [{:keys [port host transport-fn tls-keys-str tls-keys-file]}]
  (let [tls-context (tls/ssl-context-or-throw tls-keys-str tls-keys-file)]
    (transport-fn (tls/socket tls-context ^String host (int port) 10000))))

(defn connect
  "Connects to a socket-based REPL at the given host (defaults to 127.0.0.1) and port
   or using the supplied socket, returning the Transport (by default `nrepl.transport/bencode`)
   for that connection.

   Transports are most easily used with `client`, `client-session`, and
   `message`, depending on the semantics desired."
  [& {:keys [port host socket transport-fn tls-keys-str tls-keys-file]
      :or   {transport-fn transport/bencode
             host         "127.0.0.1"}
      :as   opts}]
  {:pre [transport-fn]}
  (cond
    socket
    (transport-fn (socket/unix-client-socket socket))

    (or tls-keys-str tls-keys-file)
    (tls-connect (assoc opts :transport-fn transport-fn :host host))

    (and host port)
    (transport-fn (java.net.Socket. ^String host (int port)))

    :else
    (throw (IllegalArgumentException. "A host plus port or a socket must be supplied to connect."))))

(defn- to-uri ^java.net.URI [x]
  {:post [(instance? java.net.URI %)]}
  (if (string? x)
    (java.net.URI. x)
    x))

(defn- socket-info
  [x]
  (let [uri (to-uri x)
        port (.getPort uri)]
    (merge {:host (.getHost uri)}
           (when (pos? port)
             {:port port}))))

(def ^{:private false} uri-scheme #(-> (to-uri %) .getScheme .toLowerCase))

(defmulti url-connect
  "Connects to an nREPL endpoint identified by the given URL/URI.  Valid
   examples include:

      nrepl://192.168.0.12:7889
      telnet://localhost:5000
      http://your-app-name.heroku.com/repl

   This is a multimethod that dispatches on the scheme of the URI provided
   (which can be a string or java.net.URI).  By default, implementations for
   nrepl (corresponding to using the default bencode transport) and
   telnet (using the `nrepl.transport/tty` transport) are
   registered.  Alternative implementations may add support for other schemes,
   such as HTTP, HTTPS, JMX, existing message queues, etc."
  uri-scheme)

;; TODO: oh so ugly
(defn- add-socket-connect-method!
  [protocol connect-defaults]
  (defmethod url-connect protocol
    [uri]
    (apply connect (mapcat identity
                           (merge connect-defaults
                                  (socket-info uri))))))

(add-socket-connect-method! "nrepl+edn" {:transport-fn transport/edn
                                         :port 7888})
(add-socket-connect-method! "nrepl" {:transport-fn transport/bencode
                                     :port 7888})
(add-socket-connect-method! "telnet" {:transport-fn transport/tty})

(defmethod url-connect :default
  [uri]
  (throw (IllegalArgumentException.
          (format "No nREPL support known for scheme %s, url %s" (uri-scheme uri) uri))))

(def ^{:deprecated "0.5.0"} version
  "Use `nrepl.version/version` instead.
  Current version of nREPL.
  Map of :major, :minor, :incremental, :qualifier, and :version-string."
  version/version)

(def ^{:deprecated "0.5.0"} version-string
  "Use `(:version-string nrepl.version/version)` instead.
  Current version of nREPL as a string.
  See also `version`."
  (:version-string version/version))
