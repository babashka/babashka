(ns babashka.impl.socket-repl
  {:no-doc true}
  (:require
   [babashka.impl.clojure.core.server :as server]
   [babashka.impl.common :as common]
   [babashka.impl.repl :as repl]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

;; this is mapped to clojure.core.server/repl in babashka.main
(def repl (fn []
            (repl/repl (common/ctx))))

(defn parse-opts [opts]
  (let [opts (str/trim opts)]
    (cond
      (str/starts-with? opts "{")
      (edn/read-string opts)

      (str/starts-with? opts "unix://")
      {:socket (subs opts (count "unix://"))
       :name "bb"
       :accept 'clojure.core.server/repl
       :args []}

      :else
      (let [parts (str/split opts #":")
            [host port] (if (= 1 (count parts))
                          [nil (Integer. ^String (first parts))]
                          [(first parts) (Integer. ^String (second parts))])]
        {:address host
         :port port
         :name "bb"
         :accept 'clojure.core.server/repl
         :args []}))))

(defn start-repl! [opts sci-ctx]
  (let [opts (parse-opts opts)
        socket (server/start-server sci-ctx opts)]
    (binding [*out* *err*]
      (println (str "Babashka socket REPL started at " (server/describe socket))))
    socket))

(defn stop-repl! []
  ;; This is only used by tests where we run one server at a time.
  (server/stop-servers))

(comment
  @#'server/servers
  (stop-repl!)
  )
