(ns babashka.impl.nrepl-server
  {:no-doc true}
  (:refer-clojure :exclude [send future binding])
  (:require [babashka.impl.bencode.core :refer [read-bencode]]
            [babashka.impl.nrepl-server.utils :refer [dev? response-for send send-exception
                                                      replying-print-writer]]
            [clojure.string :as str]
            [clojure.tools.reader.reader-types :as r]
            [sci.core :as sci]
            [sci.impl.interpreter :refer [eval-string* eval-form]]
            [sci.impl.parser :as p]
            [sci.impl.utils :as sci-utils]
            [sci.impl.vars :as vars])
  (:import [java.io InputStream PushbackInputStream EOFException BufferedOutputStream]
           [java.net ServerSocket]))

(set! *warn-on-reflection* true)

(defn eval-msg [ctx o msg]
  (try
    (let [code-str (get msg :code)
          reader (r/indexing-push-back-reader (r/string-push-back-reader code-str))
          ns-str (get msg :ns)
          sci-ns (when ns-str (sci-utils/namespace-object (:env ctx) (symbol ns-str) true nil))]
      (when @dev? (println "current ns" (vars/current-ns-name)))
      (sci/with-bindings (cond-> {}
                           sci-ns (assoc vars/current-ns sci-ns))
        (loop []
          (let [pw (replying-print-writer o msg)
                form (p/parse-next ctx reader)
                value (if (identical? :edamame.impl.parser/eof form) ::nil
                          (sci/with-bindings {sci/out pw}
                            (eval-form ctx form)))
                env (:env ctx)]
            (swap! env update-in [:namespaces 'clojure.core]
                   (fn [core]
                     (assoc core
                            '*1 value
                            '*2 (get core '*1)
                            '*3 (get core '*2))))
            (send o (response-for msg (cond-> {"ns" (vars/current-ns-name)}
                                        (not (identical? value ::nil)) (assoc "value" (pr-str value)))))
            (when (not (identical? ::nil value))
              (recur)))))
      (send o (response-for msg {"status" #{"done"}})))
    (catch Exception ex
      (swap! (:env ctx) update-in [:namespaces 'clojure.core]
             assoc '*e ex)
      (send-exception o msg ex))))

(defn fully-qualified-syms [ctx ns-sym]
  (let [syms (eval-string* ctx (format "(keys (ns-map '%s))" ns-sym))
        sym-strs (map #(str "`" %) syms)
        sym-expr (str "[" (str/join " " sym-strs) "]")
        syms (eval-string* ctx sym-expr)]
    syms))

(defn match [_alias->ns ns->alias query [sym-ns sym-name qualifier]]
  (let [pat (re-pattern query)]
    (or (when (and (identical? :unqualified qualifier) (re-find pat sym-name))
          [sym-ns sym-name])
        (when sym-ns
          (or (when (re-find pat (str (get ns->alias (symbol sym-ns)) "/" sym-name))
                [sym-ns (str (get ns->alias (symbol sym-ns)) "/" sym-name)])
              (when (re-find pat (str sym-ns "/" sym-name))
                [sym-ns (str sym-ns "/" sym-name)]))))))

(defn complete [ctx o msg]
  (try
    (let [ns-str (get msg :ns)
          sci-ns (when ns-str
                   (sci-utils/namespace-object (:env ctx) (symbol ns-str) nil false))]
      (sci/binding [vars/current-ns (or sci-ns @vars/current-ns)]
        (let [query (:symbol msg)
              from-current-ns (fully-qualified-syms ctx (eval-string* ctx "(ns-name *ns*)"))
              from-current-ns (map (fn [sym]
                                     [(namespace sym) (name sym) :unqualified])
                                   from-current-ns)
              alias->ns (eval-string* ctx "(let [m (ns-aliases *ns*)] (zipmap (keys m) (map ns-name (vals m))))")
              ns->alias (zipmap (vals alias->ns) (keys alias->ns))
              from-aliased-nss (doall (mapcat
                                       (fn [alias]
                                         (let [ns (get alias->ns alias)
                                               syms (eval-string* ctx (format "(keys (ns-publics '%s))" ns))]
                                           (map (fn [sym]
                                                  [(str ns) (str sym) :qualified])
                                                syms)))
                                       (keys alias->ns)))
              svs (concat from-current-ns from-aliased-nss)
              completions (keep (fn [entry]
                                  (match alias->ns ns->alias query entry))
                                svs)
              completions (mapv (fn [[namespace name]]
                                  {"candidate" (str name) "ns" (str namespace) #_"type" #_"function"})
                                completions)]
          (when @dev? (prn "completions" completions))
          (send o (response-for msg {"completions" completions
                                     "status" #{"done"}})))))
       (catch Throwable e
         (println e)
         (send o (response-for msg {"completions" []
                                    "status" #{"done"}})))))

(defn close-session [ctx msg _is os]
  (let [session (:session msg)]
    (swap! (:sessions ctx) disj session))
  (send os (response-for msg {"status" #{"done" "session-closed"}})))

(defn ls-sessions [ctx msg os]
  (let [sessions @(:sessions ctx)]
    (send os (response-for msg {"sessions" sessions
                                "status" #{"done"}}))))

(defn read-msg [msg]
  (-> (zipmap (map keyword (keys msg))
              (map #(if (bytes? %)
                      (String. (bytes %))
                      %) (vals msg)))
      (update :op keyword)))

(defn session-loop [ctx ^InputStream is os id]
  (when @dev? (println "Reading!" id (.available is)))
  (when-let [msg (try (read-bencode is)
                      (catch EOFException _
                        (println "Client closed connection.")))]
    (let [msg (read-msg msg)]
      (when @dev? (prn "Received" msg))
      (case (get msg :op)
        :clone (do
                 (when @dev? (println "Cloning!"))
                 (let [id (str (java.util.UUID/randomUUID))]
                   (swap! (:sessions ctx) (fnil conj #{}) id)
                   (send os (response-for msg {"new-session" id "status" #{"done"}}))
                   (recur ctx is os id)))
        :close (do (close-session ctx msg is os)
                   (recur ctx is os id))
        :eval (do
                (eval-msg ctx os msg)
                (recur ctx is os id))
        :load-file (let [file (:file msg)
                         msg (assoc msg :code file)]
                     (eval-msg ctx os msg)
                     (recur ctx is os id))
        :complete (do
                    (complete ctx os msg)
                    (recur ctx is os id))
        :describe
        (do (send os (response-for msg {"status" #{"done"}
                                        "ops" (zipmap #{"clone" "close" "eval" "load-file"
                                                        "complete" "describe" "ls-sessions"}
                                                      (repeat {}))}))
            (recur ctx is os id))
        :ls-sessions (do (ls-sessions ctx msg os)
                         (recur ctx is os id))
        ;; fallback
        (do (when @dev?
              (println "Unhandled message" msg))
            (send os (response-for msg {"status" #{"error" "unknown-op" "done"}}))
            (recur ctx is os id))))))

(defn listen [ctx ^ServerSocket listener]
  (when @dev? (println "Listening"))
  (let [client-socket (.accept listener)
        in (.getInputStream client-socket)
        in (PushbackInputStream. in)
        out (.getOutputStream client-socket)
        out (BufferedOutputStream. out)]
    (when @dev? (println "Connected."))
    (sci/future
      (sci/binding
          ;; allow *ns* to be set! inside future
          [vars/current-ns (vars/->SciNamespace 'user nil)
           sci/print-length @sci/print-length]
        (session-loop ctx in out "pre-init")))
    (recur ctx listener)))

(def server (atom nil))

(defn stop-server! []
  (when-let [s @server]
    (.close ^ServerSocket s)
    (reset! server nil)))

(defn start-server! [ctx host+port]
  (vreset! dev? (= "true" (System/getenv "BABASHKA_DEV")))
  (let [ctx (assoc ctx :sessions (atom #{}))
        parts (str/split host+port #":")
        [address port] (if (= 1 (count parts))
                         [nil (Integer. ^String (first parts))]
                         [(java.net.InetAddress/getByName (first parts))
                          (Integer. ^String (second parts))])
        host+port (if-not address (str "localhost:" port)
                          host+port)]
    (println "Starting nREPL server at" host+port)
    (let [socket-server (new ServerSocket port 0 address)]
      (reset! server socket-server)
      (listen ctx socket-server))))
