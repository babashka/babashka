(ns babashka.impl.nrepl-server
  {:no-doc true}
  (:require
   [babashka.impl.clojure.core]
   [babashka.impl.common :as common]
   [babashka.nrepl.server :as server]
   [sci.core :as sci]))

(defn start-server!
  ([]
   (start-server! nil))
  ([opts]
   (let [dev? (= "true" (System/getenv "BABASHKA_DEV"))
         opts (merge {:debug dev?
                      :describe {"versions" {"babashka" common/version}}
                      :thread-bind [babashka.impl.clojure.core/warn-on-reflection]}
                     opts)]
     (server/start-server! @common/ctx opts))))

(def nrepl-server-namespace
  (let [ns-sci (sci/create-ns 'babashka.nrepl.server)]
    {'start-server! (sci/copy-var start-server! ns-sci)
     'stop-server! (sci/copy-var server/stop-server! ns-sci)}))
