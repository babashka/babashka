(ns babashka.impl.nrepl-server
  {:no-doc true}
  (:require [babashka.impl.common :as common]
            [babashka.nrepl.server :as server]
            [sci.core :as sci]))

(defn start-server!
  ([]
   (server/start-server! @common/ctx))
  ([opts]
   (server/start-server! @common/ctx opts)))

(def nrepl-server-namespace
  (let [ns-sci (create-ns 'babashka.nrepl.server)]
    {'start-server! (sci/copy-var start-server! ns-sci)
     'stop-server! (sci/copy-var server/stop-server! ns-sci)}))
