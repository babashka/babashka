(ns babashka.impl.socket-repl
  {:no-doc true}
  (:require
   [babashka.impl.clojure.core.server :as server]
   [babashka.impl.repl :as repl]
   [clojure.string :as str]
   [sci.impl.opts :refer [init]]))

(set! *warn-on-reflection* true)

(defn start-repl! [host+port sci-opts]
  (let [parts (str/split host+port #":")
        [host port] (if (= 1 (count parts))
                      [nil (Integer. ^String (first parts))]
                      [(first parts) (Integer. ^String (second parts))])
        host+port (if-not host (str "localhost:" port)
                          host+port)
        sci-ctx (init sci-opts)
        socket (server/start-server
                {:address host
                 :port port
                 :name "bb"
                 :accept babashka.impl.repl/repl
                 :args [sci-ctx]})]
    (println "Babashka socket REPL started at" host+port)
    socket))

(defn stop-repl! []
  (server/stop-server))

(comment
  (def sock (start-repl! "0.0.0.0:1666" {:env (atom {})}))
  @#'server/servers
  (stop-repl!)
  )
