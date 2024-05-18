(ns babashka.impl.httpkit-server
  (:require [org.httpkit.server :as server]
            [sci.core :as sci :refer [copy-var]]))

(def sns (sci/create-ns 'org.httpkit.server nil))

(def httpkit-server-namespace
  {:obj sns
   'server-stop!              (copy-var server/server-stop! sns)
   'server-port               (copy-var server/server-port sns)
   'server-status             (copy-var server/server-status sns)
   'run-server                (copy-var server/run-server sns)
   'sec-websocket-accept      (copy-var server/sec-websocket-accept sns)
   'websocket-handshake-check (copy-var server/websocket-handshake-check sns)
   'send-checked-websocket-handshake! (copy-var server/send-checked-websocket-handshake! sns)
   'send-websocket-handshake! (copy-var server/send-websocket-handshake! sns)
   'as-channel                (copy-var server/as-channel sns)
   'send!                     (copy-var server/send! sns)
   'with-channel              (copy-var server/with-channel sns)
   'on-close                  (copy-var server/on-close sns)
   'close                     (copy-var server/close sns)}
  )
