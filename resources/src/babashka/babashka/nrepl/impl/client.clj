(ns babashka.nrepl.impl.client
  "nREPL client for `bb repl --connect`."
  {:no-doc true}
  (:require [nrepl.core :as nrepl]))

(defn- describe [client]
  (let [{:keys [versions ops]} (nrepl/combine-responses (nrepl/message client {:op "describe"}))]
    {:versions (into {} (map (fn [[k v]] [(name k) (:version-string v)])) versions)
     :ops (set (map name (keys ops)))}))

(defn connect
  "Connects to `host` and `port` or a Unix domain `socket`.
  Returns a map with `:eval`, `:completions`, `:lookup`, `:stdin` (input the
  server asked for, an empty string for EOF), `:interrupt` and `:close` functions, `:describe` server information and a `:ns` atom
  containing the current namespace. `:eval` accepts code and a reply callback."
  [{:keys [host port socket]}]
  (let [conn (if socket
               (nrepl/connect :socket socket)
               (nrepl/connect :host host :port port))
        client (nrepl/client conn Long/MAX_VALUE)
        session (nrepl/client-session client)
        {:keys [ops] :as info} (describe client)
        ns-name (atom "user")
        current-id (atom nil)]
    {:describe info
     :ns ns-name
     :eval (fn [code on-reply]
             (let [id (str (java.util.UUID/randomUUID))]
               (reset! current-id id)
               (try
                 (doseq [{:keys [ns] :as reply} (nrepl/message session {:op "eval" :code code :id id})]
                   (when ns (reset! ns-name ns))
                   (on-reply reply))
                 (finally (reset! current-id nil)))))
     :completions (fn [prefix]
                    (let [op (if (contains? ops "completions") "completions" "complete")
                          reply (nrepl/combine-responses
                                 (nrepl/message session {:op op :prefix prefix :ns @ns-name}))]
                      {:completions (mapv (fn [{:keys [candidate ns type]}]
                                            {:candidate candidate :ns ns :type type})
                                          (:completions reply))}))
     :lookup (fn [sym]
               (let [{:keys [info]} (nrepl/combine-responses
                                     (nrepl/message session {:op "lookup" :sym sym :ns @ns-name}))]
                 (when (seq info)
                   {:ns (:ns info) :name (:name info) :doc (:doc info)
                    :arglists (some-> (:arglists-str info) read-string)})))
     :stdin (fn [s] (doall (nrepl/message session {:op "stdin" :stdin s})))
     :interrupt (fn []
                  (when-let [id @current-id]
                    (doall (nrepl/message session {:op "interrupt" :interrupt-id id}))))
     :close (fn []
              (try (doall (nrepl/message session {:op "close"}))
                   (finally (.close ^java.io.Closeable conn))))}))
