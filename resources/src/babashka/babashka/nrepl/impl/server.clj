(ns babashka.nrepl.impl.server
  "babashka's nREPL server: nREPL's own server and middleware stack, run
  from bundled source, plus the ops babashka adds."
  {:no-doc true}
  (:require
   [babashka.fs :as fs]
   [babashka.nrepl.impl.clojuredocs :as clojuredocs]
   [babashka.nrepl.impl.java :as java]
   [babashka.nrepl.impl.sci :as sci-helpers]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.misc :as misc]
   [nrepl.server :as server]
   [nrepl.transport :as t]))

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

(defn complete-reply
  "Returns the completions of the message's `prefix` or `symbol`."
  [{:keys [symbol prefix] :as msg}]
  (let [query (or symbol prefix)]
    {:status :done
     :completions (if query
                    (binding [*ns* (msg-ns msg)]
                      (:completions (sci-helpers/completions query)))
                    [])}))

(defn- forms-join [forms]
  (str/join \newline (map pr-str forms)))

(defn- file-url
  "Returns the file or jar URL of file, a classpath resource or an absolute
  path, or nil if file names neither."
  [file]
  (when-let [url (or (io/resource file)
                     (when (and (fs/exists? file) (fs/absolute? file))
                       (io/as-url (fs/file file))))]
    (let [url (str url)]
      (when (re-find #"^(file|jar):" url) url))))

(defn- ns-file
  "Returns the URL of the file a var of the-ns comes from, or of the file
  the-ns loads from, or nil if it has neither."
  [the-ns]
  (or (some file-url (distinct (keep (comp :file meta) (vals (ns-interns the-ns)))))
      (some file-url (sci-helpers/resource-paths (ns-name the-ns)))))

(defn- ns-lookup
  "Returns the lookup result of the namespace that sym-str names, as an alias
  in the message's namespace or by its own name, or nil if it names none."
  [sym-str {:keys [ns] :as msg}]
  (when-let [sym (some-> sym-str symbol)]
    (let [context (if ns (find-ns (symbol ns)) (msg-ns msg))]
      (when-let [the-ns (or (some-> context ns-aliases (get sym)) (find-ns sym))]
        (let [file (ns-file the-ns)]
          (cond-> {:ns (ns-name the-ns)
                   :name (ns-name the-ns)
                   :doc (:doc (meta the-ns))}
            file (assoc :file file :line 1)))))))

(defn lookup-reply
  "Returns the info of the message's `sym`, or its eldoc if `op` is `eldoc` or
  `cider/eldoc`."
  [{:keys [op sym symbol ns class member] :as msg}]
  (let [eldoc? (contains? #{"eldoc" "cider/eldoc"} op)
        sym-str (or sym symbol)
        ns-str (or ns (str (ns-name (msg-ns msg))))
        m (when-not (seq class) (sci-helpers/lookup sym-str ns-str))
        java (when (nil? m)
               (when-let [info (java/info {:ns ns-str :sym sym-str :class class :member member})]
                 (if eldoc? (java/eldoc info) (java/info-reply info))))
        m (or m (when-not (or java eldoc? (seq class))
                  (ns-lookup sym-str msg)))
        {:keys [doc file line column arglists forms special-form macro added deprecated]} m]
    (cond
      java (assoc java :status :done)
      (nil? m) {:status (if eldoc? #{:done :no-eldoc} #{:done :no-info})}
      eldoc? (cond-> {:status :done
                      :ns (:ns m)
                      :name (:name m)
                      :eldoc (if special-form
                               (mapv #(if (coll? %) (mapv str %) [(str %)]) forms)
                               (mapv #(mapv str %) arglists))
                      :type (cond special-form "special-form"
                                  macro "macro"
                                  (ifn? (:val m)) "function"
                                  :else "variable")}
               doc (assoc :docstring doc))
      :else (let [see-also (clojuredocs/see-also m)]
              (cond-> {:status :done
                       :ns (:ns m)
                       :name (:name m)}
                arglists (assoc :arglists-str (forms-join arglists))
                special-form (assoc :special-form "true"
                                    :forms-str (forms-join forms)
                                    :url (str "https://clojure.org/" (or (:url m) (str "special_forms#" (:name m)))))
                doc (assoc :doc doc)
                macro (assoc :macro "true")
                added (assoc :added (str added))
                deprecated (assoc :deprecated (str deprecated))
                file (assoc :file file)
                line (assoc :line line)
                column (assoc :column column)
                see-also (assoc :see-also see-also))))))

(defn wrap-babashka
  "Adds babashka's versions to `describe` replies."
  [h]
  (fn [{:keys [op] :as msg}]
    (if (= "describe" op)
      (h (assoc msg :transport (versions-transport (:transport msg))))
      (h msg))))

(set-descriptor! #'wrap-babashka
                 {:requires #{"clone"}
                  :expects #{}
                  :handles {}})

(def ^:private cider-middleware
  "The cider-nrepl middleware bb runs.
  content-type, format, log, out, refresh, reload, slurp, tap and xref do not
  run in bb."
  '[cider.nrepl/wrap-apropos
    cider.nrepl/wrap-classpath
    cider.nrepl/wrap-clojuredocs
    cider.nrepl/wrap-complete
    cider.nrepl/wrap-debug
    cider.nrepl/wrap-enlighten
    cider.nrepl/wrap-info
    cider.nrepl/wrap-inspect
    cider.nrepl/wrap-macroexpand
    cider.nrepl/wrap-ns
    cider.nrepl/wrap-profile
    cider.nrepl/wrap-resource
    cider.nrepl/wrap-spec
    cider.nrepl/wrap-stacktrace
    cider.nrepl/wrap-test
    cider.nrepl/wrap-trace
    cider.nrepl/wrap-tracker
    cider.nrepl/wrap-undef
    cider.nrepl/wrap-version])

(defn- middleware-var [m]
  (if (symbol? m) (requiring-resolve m) m))

(defn start-server!
  "Starts the server. Returns a map with `:socket`, `:port` and `:stop`.

  `wrap-handler` wraps the message handler, see babashka.nrepl.server.
  Options: `:host` (default 127.0.0.1), `:port` (default 1667), `:socket`
  (a unix domain socket path, instead of host and port), `:quiet`,
  `:describe` (a map merged into the describe reply, `versions` included),
  `:middleware` (vars or symbols with an nREPL descriptor, added to the
  default stack)."
  [{:keys [host port socket quiet describe middleware]
    :or {host "127.0.0.1" port 1667}}
   wrap-handler]
  (reset! versions (walk/keywordize-keys (get describe "versions" (:versions describe))))
  (let [handler (wrap-handler (apply server/default-handler #'wrap-babashka
                                     (concat (map requiring-resolve cider-middleware)
                                             (map middleware-var middleware))))
        srv (if socket
              (server/start-server :socket socket :handler handler)
              (server/start-server :bind host :port port :handler handler))
        ss (:server-socket srv)
        open? (if socket
                (fn [] (.isOpen ^java.nio.channels.ServerSocketChannel ss))
                (fn [] (not (.isClosed ^java.net.ServerSocket ss))))]
    (when-not quiet
      (println (if socket
                 (str "Started nREPL server at " socket)
                 (format "Started nREPL server at %s:%d"
                         (.getHostAddress (.getInetAddress ^java.net.ServerSocket ss))
                         (.getLocalPort ^java.net.ServerSocket ss)))))
    ;; nREPL runs on daemon threads, this keeps the process alive
    (doto (Thread. (fn [] (while (open?) (Thread/sleep 250))))
      (.setName "babashka-nrepl-server")
      (.setDaemon false)
      (.start))
    {:socket ss
     :port (when-not socket (.getLocalPort ^java.net.ServerSocket ss))
     :path socket
     :stop (fn [] (server/stop-server srv))}))
