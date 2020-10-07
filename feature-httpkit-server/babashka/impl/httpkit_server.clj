(ns babashka.impl.httpkit-server
  (:require [org.httpkit.server :as server]
            [sci.core :as sci :refer [copy-var]]))

(def warning
  "WARNING: the org.httpkit.server namespace is experimental and may sat or be removed in future versions of babashka, depending on feedback from the community.
Please leave a note at https://github.com/borkdude/babashka/issues/556 to let us know how you are using it.
You can turn this warning off using -Dbabashka.httpkit-server.warning=false
This namespace will remain available under a feature flag, see https://github.com/borkdude/babashka/blob/master/doc/build.md#feature-flags")

(def sns (sci/create-ns 'org.httpkit.server
                        {:sci.impl/required-fn (fn [_]
                                                 (when-not (= "false" (System/getProperty "babashka.httpkit-server.warning"))
                                                   (binding [*out* @sci/err]
                                                     (println warning))))}))

(def httpkit-server-namespace
  {:obj sns
   'server-stop!              (copy-var server/server-stop! sns)
   'server-port               (copy-var server/server-port sns)
   'run-server                (copy-var server/run-server sns)
   'sec-websocket-accept      (copy-var server/sec-websocket-accept sns)
   'websocket-handshake-check (copy-var server/websocket-handshake-check sns)
   'send-checked-websocket-handshake! (copy-var server/send-checked-websocket-handshake! sns)
   'send-websocket-handshake! (copy-var server/send-websocket-handshake! sns)
   'as-channel                (copy-var server/as-channel sns)
   'send!                     (copy-var server/send! sns)})
