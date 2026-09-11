(ns babashka.nrepl.server
  "Starts babashka's nREPL server, which runs from bundled source in the
  given sci context."
  {:author "Michiel Borkent"}
  (:require [clojure.string :as string]
            [sci.core :as sci]
            [sci.ctx-store :as ctx-store]))

(set! *warn-on-reflection* true)

(defn parse-opt [host+port]
  (let [parts (string/split host+port #":")
        [host port] (if (= 1 (count parts))
                      [nil (Integer. ^String (first parts))]
                      [(first parts)
                       (Integer. ^String (second parts))])]
    {:host host
     :port port}))

(defn start-server!
  "Starts the server in sci context `ctx`. See
  `babashka.nrepl.impl.server/start-server!` for the options."
  [ctx & [opts]]
  (let [;; nREPL handles messages on its own threads, which carry no
        ;; bindings, so the handler re-establishes the context
        wrap-handler (fn [h] (fn [msg] (ctx-store/with-ctx ctx (h msg))))]
    (sci/eval-form ctx (list 'do
                             '(require 'babashka.nrepl.impl.server)
                             (list 'babashka.nrepl.impl.server/start-server!
                                   (list 'quote (into {} (remove (comp nil? val)) opts))
                                   wrap-handler)))))

(defn stop-server! [server]
  ((:stop server)))
