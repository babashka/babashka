(ns nrepl.ack
  "Server acknowledgement support.

  When starting a new nREPL server, it can notify an already-running
  server of its port via the ack protocol. This is used by tools like
  Leiningen to discover the port of a freshly launched server."
  {:added "0.4"}
  (:require
   [nrepl.core :as nrepl]
   [nrepl.transport :as t]))

;; could be a lot fancier, but it'll do for now
(def ^{:private true} ack-port-promise (atom nil))

(defn reset-ack-port!
  "Reset the ack port promise. Must be called before starting a server
  whose port should be captured via `wait-for-ack`."
  []
  (reset! ack-port-promise (promise))
  ;; save people the misery of ever trying to deref the empty promise in their REPL
  nil)

(defn wait-for-ack
  "Waits for a presumably just-launched nREPL server to connect and
   deliver its port number.  Returns that number if it's delivered
   within `timeout` ms, otherwise nil.  Assumes that `ack`
   middleware has been applied to the local nREPL server handler.

   Expected usage:

   (reset-ack-port!)
   (start-server already-running-server-port)
   => (wait-for-ack)
   59872 ; the port of the server started via start-server"
  [timeout]
  (let [f (future @@ack-port-promise)]
    (deref f timeout nil)))

(defn handle-ack
  "Middleware that handles the \"ack\" op by delivering the port value
  to the ack port promise."
  [h]
  (fn [{:keys [op port transport] :as msg}]
    (if (not= op "ack")
      (h msg)
      (try
        (deliver @ack-port-promise port)
        (t/send transport {:status :done})
        (catch Throwable _e)))))

;; TODO: could stand to have some better error handling around all of this
(defn send-ack
  "Send an ack message to a running nREPL server at `ack-port`,
  informing it that this server is listening on `my-port`."
  ([my-port ack-port]
   (send-ack my-port ack-port t/bencode))
  ([my-port ack-port transport-fn]
   (with-open [^java.io.Closeable transport (nrepl/connect :transport-fn transport-fn
                                                           :port ack-port)]
     (let [client (nrepl/client transport 1000)]
       ;; consume response from the server, solely to let that side
       ;; finish cleanly without (by default) spewing a SocketException when
       ;; the ack client goes away suddenly
       (dorun (nrepl/message client {:op "ack" :port my-port}))))))
