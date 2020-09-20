(ns babashka.impl.httpkit-server
  (:require [org.httpkit.server :as server]
            [sci.core :as sci :refer [copy-var]]))

(def sns (sci/create-ns 'org.httpkit.server
                        {:sci.impl/on-loaded (fn [_]
                                               (println :hello))}))

(def httpkit-server-namespace
  {'server-stop!              (copy-var server/server-stop! sns)
   'run-server                (copy-var server/run-server sns)
   'sec-websocket-accept      (copy-var server/sec-websocket-accept sns)
   'websocket-handshake-check (copy-var server/websocket-handshake-check sns)
   'send-checked-websocket-handshake! (copy-var server/send-checked-websocket-handshake! sns)
   'send-websocket-handshake! (copy-var server/send-websocket-handshake! sns)
   'as-channel                (copy-var server/as-channel sns)})
