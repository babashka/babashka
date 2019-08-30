(ns babashka.impl.socket-repl
  (:require [babashka.impl.clojure.server :as server]
            [babashka.impl.clojure.main :as m]
            [sci.core :refer [eval-string]])
  (:import [babashka.impl LockFix]))

(def env (atom {}))

(defn repl
  "REPL with predefined hooks for attachable socket server."
  []
  (m/repl :eval (fn [expr]
                  (eval-string (str expr) {:env env
                                           :bindings {'*1 *1
                                                      '*2 *2
                                                      '*3 *3
                                                      '*e *e}}))))

(defn start-repl! [port]
  (prn "REPL!" port)
  (server/start-server
   {:port port
    :name "bb"
    :accept babashka.impl.socket-repl/repl}))



