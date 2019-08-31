(ns babashka.impl.socket-repl
  (:require [babashka.impl.clojure.server :as server]
            [babashka.impl.clojure.main :as m]
            [sci.core :refer [eval-string]]
            [sci.impl.parser :as parser]
            [sci.impl.toolsreader.v1v3v2.clojure.tools.reader.reader-types :as r]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [babashka.impl LockFix]))

(set! *warn-on-reflection* true)

(defn repl
  "REPL with predefined hooks for attachable socket server."
  [sci-opts]
  (m/repl
   :init #(do (println "Babashka"
                       (str "v" (str/trim (slurp (io/resource "BABASHKA_VERSION"))))
                       "REPL.")
              (println "Use :repl/quit or :repl/exit to quit the REPL.")
              (println "Clojure rocks, Bash reaches.")
              (println))
   :read (fn [request-prompt request-exit]
           (let [in (r/indexing-push-back-reader (r/push-back-reader *in*))
                 p (r/peek-char in)]
             (if (= \newline p)
               (do (r/read-char in) request-prompt)
               (let [v (parser/parse-next {} in)]
                 (if (or (identical? :repl/quit v)
                         (identical? :repl/exit v))
                   request-exit
                   v)))))
   :eval (fn [expr]
           (eval-string (str expr)
                        (update sci-opts
                                :bindings
                                merge {'*1 *1
                                       '*2 *2
                                       '*3 *3
                                       '*e *e})))))

(defn start-repl! [host+port sci-opts]
  (let [parts (str/split host+port #":")
        [host port] (if (= 1 (count parts))
                      [nil (Integer. ^String (first parts))]
                      [(first parts) (Integer. ^String (second parts))])
        host+port (if-not host (str "localhost:" port)
                          host+port)
        socket (server/start-server
                {:address host
                 :port port
                 :name "bb"
                 :accept babashka.impl.socket-repl/repl
                 :args [sci-opts]})]
    (println "Babashka socket REPL started at" host+port)
    socket))

(comment
  (def sock (start-repl! "0.0.0.0:1666" {:env (atom {})}))
  (.accept sock)
  @#'server/servers
  (server/stop-server "bb")
  )
