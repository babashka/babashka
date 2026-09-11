#!/usr/bin/env bb

;; An nREPL server with middleware of your own: every `eval` is timed, the
;; time goes into the reply as `elapsed-ms` and to the server's terminal.
;;
;; Run:  bb examples/nrepl_middleware.clj
;; Then connect an editor to port 1667, or from another terminal:
;;   bb -e "(require '[nrepl.core :as nrepl])
;;          (with-open [conn (nrepl/connect :port 1667)]
;;            (let [client (nrepl/client conn 1000)]
;;              (prn (nrepl/message client {:op \"eval\" :code \"(Thread/sleep 100)\"}))))"

(ns nrepl-middleware
  (:require [babashka.nrepl.server :as nrepl-server]
            [nrepl.middleware :refer [set-descriptor!]]
            [nrepl.transport :as t]))

(defn- timing-transport
  "The transport of an eval message, adding `elapsed-ms` to the reply that
  ends the eval."
  [{:keys [transport code]} started]
  (reify t/Transport
    (recv [_ timeout] (t/recv transport timeout))
    (send [this reply]
      (if (contains? (set (:status reply)) :done)
        (let [ms (quot (- (System/nanoTime) started) 1000000)]
          (println (format "%5d ms  %s" ms code))
          (t/send transport (assoc reply :elapsed-ms ms)))
        (t/send transport reply))
      this)))

(defn wrap-timing
  "Times every `eval`, passes everything else on."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "eval" op)
      (handler (assoc msg :transport (timing-transport msg (System/nanoTime))))
      (handler msg))))

;; The descriptor places the middleware in the stack: it must run before
;; the eval it wraps.
(set-descriptor! #'wrap-timing
                 {:requires #{"clone"}
                  :expects #{"eval"}
                  :handles {}})

(nrepl-server/start-server! {:port 1667 :middleware [#'wrap-timing]})
