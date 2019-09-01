(ns babashka.impl.socket-repl
  {:no-doc true}
  (:require
   [babashka.impl.clojure.core.server :as server]
   [babashka.impl.clojure.main :as m]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [sci.core :refer [eval-string]]
   [sci.impl.parser :as parser]
   [sci.impl.toolsreader.v1v3v2.clojure.tools.reader.reader-types :as r]
   [sci.impl.toolsreader.v1v3v2.clojure.tools.reader.edn :as ednr]))

(set! *warn-on-reflection* true)

(defn skip-newlines
  [reader]
  (loop []
    (let [c (r/read-char reader)]
      (if (= \newline c)
        (recur)
        (do (r/unread reader c)
            reader)))))

(defn repl
  "REPL with predefined hooks for attachable socket server."
  [sci-opts]
  (let [in (r/indexing-push-back-reader (r/push-back-reader *in*))]
    (m/repl
     :init #(do (println "Babashka"
                         (str "v" (str/trim (slurp (io/resource "BABASHKA_VERSION"))))
                         "REPL.")
                (println "Use :repl/quit or :repl/exit to quit the REPL.")
                (println "Clojure rocks, Bash reaches.")
                (println))
     :read (fn [request-prompt request-exit]
             (if-let [p (r/peek-char in)]
               (do #_(prn "PEEKED CHAR" (int p))
                   (if false #_(= \newline p)
                     (do (prn "REQ PROMPT")#_(r/read-char in) #_request-prompt)
                     (let [v (parser/parse-next {} in)]
                       (if (or (identical? :repl/quit v)
                               (identical? :repl/exit v))
                         request-exit
                         v))))
               request-exit))
     :eval (fn [expr]
             (let [ret (eval-string (pr-str expr)
                                   (update sci-opts
                                           :bindings
                                           merge {'*1 *1
                                                  '*2 *2
                                                  '*3 *3
                                                  '*e *e}))]
               ret))
     :need-prompt (fn [] #_(prn "PK" (r/peek-char *in*))
                    #_(prn "NEED?" (= \newline (r/peek-char *in*)))
                    #_(= \newline (r/peek-char *in*))
                    true))))

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

(defn stop-repl! []
  (server/stop-server))

(comment
  (def sock (start-repl! "0.0.0.0:1666" {:env (atom {})}))
  (.accept sock)
  @#'server/servers
  (stop-repl!)

  (let [r (r/indexing-push-back-reader (r/push-back-reader "1\n"))]
    (parser/parse-next {} r)
    (r/peek-char r))
  
  )
