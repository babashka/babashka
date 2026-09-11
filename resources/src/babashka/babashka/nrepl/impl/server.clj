(ns babashka.nrepl.impl.server
  "babashka's nREPL server: nREPL's own server and middleware stack, run
  from bundled source, plus the ops babashka adds."
  {:no-doc true}
  (:require
   [babashka.classpath :as cp]
   [babashka.nrepl.impl.sci :as sci-helpers]
   [clojure.string :as str]
   [clojure.walk :as walk]
   ;; nrepl.core for clients whose init reads nrepl.core/version, like REPLy
   [nrepl.core]
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.misc :as misc]
   [nrepl.server :as server]
   [nrepl.transport :as t :refer [safe-handle]]))

(defonce ^:private versions
  (atom {}))

(defn- versions-transport
  "A transport that adds babashka's versions to a describe reply."
  [transport]
  (reify t/Transport
    (recv [_this] (t/recv transport))
    (recv [_this timeout] (t/recv transport timeout))
    (send [this resp]
      (t/send transport (cond-> resp
                          (:versions resp) (update :versions merge @versions)))
      this)))

(defn- msg-ns
  "The namespace an op works in: the message's `ns`, else the session's."
  [{:keys [ns] :as msg}]
  (or (when ns (find-ns (symbol ns)))
      (misc/resolve-in-session msg *ns*)))

(defn- complete-reply [{:keys [symbol prefix] :as msg}]
  (let [query (or symbol prefix)]
    {:status :done
     :completions (if query
                    (binding [*ns* (msg-ns msg)]
                      (:completions (sci-helpers/completions query)))
                    [])}))

(defn- forms-join [forms]
  (str/join \newline (map pr-str forms)))

(defn- lookup-reply [{:keys [op sym symbol ns] :as _msg}]
  (let [sym-str (or sym symbol)
        m (sci-helpers/lookup sym-str ns)
        {:keys [doc file line arglists]} m]
    (cond
      (nil? m) {:status (if (= "eldoc" op) #{:done :no-eldoc} :done)}
      (= "eldoc" op) (cond-> {:status :done
                              :ns (:ns m)
                              :name (:name m)
                              :eldoc (mapv #(mapv str %) arglists)
                              :type (if (ifn? (:val m)) "function" "variable")}
                       doc (assoc :docstring doc))
      :else (cond-> {:status :done
                     :ns (:ns m)
                     :name (:name m)
                     :arglists-str (forms-join arglists)}
              doc (assoc :doc doc)
              file (assoc :file file)
              line (assoc :line line)))))

(defn- ns-list-reply [{:keys [exclude-patterns]}]
  (let [patterns (map re-pattern exclude-patterns)]
    {:status :done
     :ns-list (->> (all-ns)
                   (map (comp name ns-name))
                   (remove (fn [n] (some #(re-find % n) patterns)))
                   sort)}))

(defn- classpath-reply [_msg]
  {:status :done
   :classpath (cp/split-classpath (cp/get-classpath))})

(defn wrap-babashka
  "Middleware for the ops babashka adds to nREPL's: `complete` (the old name
  of `completions`), `eldoc`, `info`, `ns-list` and `classpath`, and
  babashka's versions in `describe`."
  [h]
  (fn [{:keys [op] :as msg}]
    (if (= "describe" op)
      (h (assoc msg :transport (versions-transport (:transport msg))))
      (safe-handle msg
        "complete" complete-reply
        "eldoc" lookup-reply
        "info" lookup-reply
        "ns-list" ns-list-reply
        "classpath" classpath-reply
        :else h))))

(set-descriptor! #'wrap-babashka
                 {:requires #{"clone"}
                  :expects #{}
                  :handles {"complete"
                            {:doc "Completions, as `completions` but keyed `symbol`."
                             :requires {"symbol" "The prefix to complete."}
                             :optional {"ns" "The namespace to complete in. Defaults to the session's."}
                             :returns {"completions" "Maps with `candidate`, `type` and `ns`."}}
                            "eldoc"
                            {:doc "Arglists and docstring of a symbol."
                             :requires {"sym" "The symbol to look up."}
                             :optional {"ns" "The namespace to resolve in."}
                             :returns {"eldoc" "The arglists, each a list of strings."}}
                            "info"
                            {:doc "Metadata of a symbol."
                             :requires {"sym" "The symbol to look up."}
                             :optional {"ns" "The namespace to resolve in."}
                             :returns {}}
                            "ns-list"
                            {:doc "The names of all namespaces."
                             :requires {}
                             :optional {"exclude-patterns" "Regexes of namespaces to leave out."}
                             :returns {"ns-list" "The sorted names."}}
                            "classpath"
                            {:doc "The classpath entries."
                             :requires {}
                             :optional {}
                             :returns {"classpath" "A list of paths."}}}})

(defn- middleware-var [m]
  (if (symbol? m) (requiring-resolve m) m))

(defn start-server!
  "Starts the server. Returns a map with `:socket`, `:port` and `:stop`.

  `wrap-handler` wraps the message handler, see babashka.nrepl.server.
  Options: `:host` (default 0.0.0.0), `:port` (default 1667), `:quiet`,
  `:describe` (a map merged into the describe reply, `versions` included),
  `:middleware` (vars or symbols with an nREPL descriptor, added to the
  default stack)."
  [{:keys [host port quiet describe middleware]
    :or {host "0.0.0.0" port 1667}}
   wrap-handler]
  (reset! versions (walk/keywordize-keys (get describe "versions" (:versions describe))))
  (let [handler (wrap-handler (apply server/default-handler #'wrap-babashka
                                     (map middleware-var middleware)))
        srv (server/start-server :bind host :port port :handler handler)
        ^java.net.ServerSocket ss (:server-socket srv)]
    (when-not quiet
      (println (format "Started nREPL server at %s:%d"
                       (.getHostAddress (.getInetAddress ss)) (.getLocalPort ss))))
    ;; nREPL runs on daemon threads, this keeps the process alive
    (doto (Thread. (fn [] (while (not (.isClosed ss)) (Thread/sleep 250))))
      (.setName "babashka-nrepl-server")
      (.setDaemon false)
      (.start))
    {:socket ss
     :port (.getLocalPort ss)
     :stop (fn [] (server/stop-server srv))}))
