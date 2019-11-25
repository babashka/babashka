(ns babashka.impl.socket-repl
  {:no-doc true}
  (:require
   [babashka.impl.clojure.core.server :as server]
   [babashka.impl.clojure.main :as m]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.reader.reader-types :as r]
   [sci.impl.interpreter :refer [opts->ctx eval-form]]
   [sci.impl.parser :as parser]))

(set! *warn-on-reflection* true)

(defn repl
  "REPL with predefined hooks for attachable socket server."
  [sci-ctx]
  (let [in (r/indexing-push-back-reader (r/push-back-reader *in*))]
    (m/repl
     :init #(do (println "Babashka"
                         (str "v" (str/trim (slurp (io/resource "BABASHKA_VERSION"))))
                         "REPL.")
                (println "Use :repl/quit or :repl/exit to quit the REPL.")
                (println "Clojure rocks, Bash reaches.")
                (println))
     :read (fn [_request-prompt request-exit]
             (if (r/peek-char in) ;; if this is nil, we reached EOF
               (let [v (parser/parse-next in #{:bb} {:current (-> sci-ctx :env deref :current-ns)})]
                 (if (or (identical? :repl/quit v)
                         (identical? :repl/exit v)
                         (identical? :edamame.impl.parser/eof v))
                   request-exit
                   v))
               request-exit))
     :eval (fn [expr]
             (let [ret (eval-form (update sci-ctx
                                          :env
                                          (fn [env]
                                            (swap! env update-in [:namespaces 'clojure.core]
                                                   assoc
                                                   '*1 *1
                                                   '*2 *2
                                                   '*3 *3
                                                   '*e *e)
                                            env))
                                  expr)]
               ret))
     :need-prompt (fn [] true)
     :prompt #(printf "%s=> " (-> sci-ctx :env deref :current-ns)))))

(defn start-repl! [host+port sci-opts]
  (let [parts (str/split host+port #":")
        [host port] (if (= 1 (count parts))
                      [nil (Integer. ^String (first parts))]
                      [(first parts) (Integer. ^String (second parts))])
        host+port (if-not host (str "localhost:" port)
                          host+port)
        sci-ctx (opts->ctx sci-opts)
        socket (server/start-server
                {:address host
                 :port port
                 :name "bb"
                 :accept babashka.impl.socket-repl/repl
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
