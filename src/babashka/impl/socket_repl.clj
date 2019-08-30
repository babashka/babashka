(ns babashka.impl.socket-repl
  (:require [babashka.impl.clojure.server :as server]
            [babashka.impl.clojure.main :as m]
            [sci.core :refer [eval-string]]
            [sci.impl.parser :as parser]
            [sci.impl.toolsreader.v1v3v2.clojure.tools.reader.reader-types :as r]
            [sci.impl.toolsreader.v1v3v2.clojure.tools.reader.edn :as edn]
            [sci.core :as sci])
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
                                                      '*e *e}}))
          :read (fn [request-prompt request-exit]
                  (let [in (r/indexing-push-back-reader (r/push-back-reader *in*))]
                    (parser/parse-next {} in)))))

#_(defn accept []
  (prn "ACCEPT!")
  (def i *in*)
  @(promise))

(defn start-repl! [port]
  (prn "REPL!" port)
  (server/start-server
   {:port port
    :name "bb"
    :accept babashka.impl.socket-repl/repl}))

(comment
  (start-repl! 1666)
  (server/stop-server "bb")
 )

