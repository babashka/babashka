(ns babashka.impl.socket-repl
  (:require [clojure.core.server :as server]
            [clojure.main :as m]
            [sci.core :refer [eval-string]]))

(defn repl
  "REPL with predefined hooks for attachable socket server."
  []
  (m/repl :eval (fn [expr]
                  (eval-string (str expr)))))

(defn start-repl! [port]
  (prn "REPL!" port)
  (server/start-server
   {:port port
    :name "bb"
    :accept 'babashka.impl.socket-repl/repl}))



