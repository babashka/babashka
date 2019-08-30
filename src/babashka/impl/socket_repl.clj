(ns babashka.impl.socket-repl
  (:require [babashka.impl.clojure.server :as server]
            [babashka.impl.clojure.main :as m]
            [sci.core :refer [eval-string]]
            [sci.impl.parser :as parser]
            [sci.impl.toolsreader.v1v3v2.clojure.tools.reader.reader-types :as r])
  (:import [babashka.impl LockFix]))

(defn repl
  "REPL with predefined hooks for attachable socket server."
  [sci-opts]
  (m/repl :eval (fn [expr]
                  (eval-string (str expr) (update sci-opts :bindings merge {'*1 *1
                                                                            '*2 *2
                                                                            '*3 *3
                                                                            '*e *e})))
          :read (fn [request-prompt request-exit]
                  (let [in (r/indexing-push-back-reader (r/push-back-reader *in*))
                        p (r/peek-char in)]
                    (case p \newline
                          (do (r/read-char in) request-prompt)
                          (parser/parse-next {} in))))))

#_(defn accept []
  (prn "ACCEPT!")
  (def i *in*)
  @(promise))

(defn start-repl! [port sci-opts]
  (server/start-server
   {:port port
    :name "bb"
    :accept babashka.impl.socket-repl/repl
    :args [sci-opts]}))

(comment
  (start-repl! 1666)
  (server/stop-server "bb")
 )

