(ns babashka.impl.nrepl-server
  {:no-doc true}
  (:require [babashka.impl.common :as common]
            [babashka.nrepl.server :refer [start-server! stop-server!]]
            [sci.core :refer [create-ns new-var copy-var]]))

(def nrepl-server-namespace
  (let [ns-sci (create-ns 'babashka.nrepl.server)]
    {'start-server! (new-var 'start-server!
                                 (fn [opts]
                                   (start-server! @common/ctx opts))
                                 {:ns ns-sci})
     'stop-server! (copy-var stop-server! ns-sci)}))