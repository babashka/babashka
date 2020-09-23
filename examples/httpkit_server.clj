(System/setProperty "babashka.httpkit-server.warning" "false")

(ns examples.httpkit-server
  (:require [clojure.pprint :refer [pprint]]
            [org.httpkit.server :as server]))

(defn handler [req]
  (let [reply (str (slurp "examples/httpkit_server.clj")
                   "---\n\n"
                   (with-out-str (pprint (dissoc req
                                                 :headers
                                                 :async-channel))))]
    {:body reply}))

(server/run-server handler {:port 8090})
@(promise) ;; wait until SIGINT

