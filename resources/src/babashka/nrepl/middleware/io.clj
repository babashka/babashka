(ns nrepl.middleware.io
  "Forward output from `System/out`, `System/err`, and root `*out*` and `*err*`
  to clients that requested such forwarding."
  (:require
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.transport :as t :refer [safe-handle]]
   [nrepl.util.out :as out]))

(defn forward-system-output-reply
  "Add msg to `tracked-sessions-map`."
  [{:keys [session] :as msg}]
  (when-let [session-id (:id (meta session))]
    ;; Idempotent - safe to call multiple times.
    (out/wrap-standard-streams)
    (doseq [stream [:out :err]]
      (out/set-callback stream session-id #(t/respond-to msg {stream %
                                                              :source :system})))
    {:status :done}))

(defn wrap-out
  "Enable forwarding of System/out and System/err output to client. To cancel
  forwarding, discard the current session and create a new one.

  NB: do not enable stdout forwarding if the client runs inside the same process
  as the server. This will lead to an infinite loop."
  [handler]
  (fn [msg]
    (safe-handle msg
      "forward-system-output" forward-system-output-reply
      :else handler)))

(set-descriptor! #'wrap-out
                 {:requires #{"clone"}
                  :expects #{"eval"}
                  :handles {"forward-system-output"
                            {:doc "Enable forwarding of System/out and System/err output to client. This is a Clojure-specific op, not part of general nREPL spec."
                             :requires {}
                             :optional {}
                             :returns {}}}})
