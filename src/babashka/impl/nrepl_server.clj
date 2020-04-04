(ns babashka.impl.nrepl-server
  {:no-doc true}
  (:refer-clojure :exclude [send future binding])
  (:require [babashka.impl.bencode.core :refer [write-bencode read-bencode]]
            [clojure.string :as str]
            [sci.core :as sci]
            [sci.impl.interpreter :refer [eval-string*]]
            [sci.impl.utils :as sci-utils]
            [sci.impl.vars :as vars])
  (:import [java.io StringWriter OutputStream InputStream PushbackInputStream EOFException]
           [java.net ServerSocket]))

(set! *warn-on-reflection* true)

(def port 1667)
(def dev? (volatile! nil))

(defn response-for [old-msg msg]
  (let [session (get old-msg :session "none")
        m (assoc msg "session" session)
        id (get old-msg :id "unknown")
        m (assoc m "id" id)]
    m))

(defn send [^OutputStream os msg]
  ;;(when @dev? (prn "Sending" msg))
  (write-bencode os msg)
  (.flush os))

(defn send-exception [os msg ^Throwable ex]
  (let [ex-map (Throwable->map ex)
        ex-name (-> ex-map :via first :type)
        cause (:cause ex-map)]
    (when @dev? (prn "sending exception" ex-map))
    (send os (response-for msg {"err" (str ex-name ": " cause "\n")}))
    (send os (response-for msg {"ex" (str "class " ex-name)
                                "root-ex" (str "class " ex-name)
                                "status" #{"eval-error"}}))
    (send os (response-for msg {"status" #{"done"}}))))

(defn eval-msg [ctx o msg #_threads]
  (try
    (let [ns-str (get msg :ns)
          sci-ns (if ns-str
                   (sci-utils/namespace-object (:env ctx) (symbol ns-str) nil false)
                   (sci-utils/namespace-object (:env ctx) 'user nil false))]
      (sci/binding [vars/current-ns sci-ns
                    sci/print-length @sci/print-length]
        (let [session (get msg :session "none")
              id (get msg :id "unknown")]
          (when @dev? (println "Registering thread for" (str session "-" id)))
          ;; (swap! threads assoc [session id] (Thread/currentThread))
          (let [code-str (get msg :code)
                sw (StringWriter.)
                value (if (str/blank? code-str)
                        ::nil
                        (sci/binding [sci/out sw
                                      vars/current-ns @vars/current-ns] (eval-string* ctx code-str)))
                out-str (not-empty (str sw))
                env (:env ctx)]
            (swap! env update-in [:namespaces 'clojure.core]
                   (fn [core]
                     (assoc core
                            '*1 value
                            '*2 (get core '*1)
                            '*3 (get core '*2))))
            (when @dev? (println "out str:" out-str))
            (when out-str
              (send o (response-for msg {"out" out-str})))
            (send o (response-for msg (cond-> {"ns" (vars/current-ns-name)}
                                        (not (identical? value ::nil)) (assoc "value" (pr-str value)))))
            (send o (response-for msg {"status" #{"done"}}))))))
    (catch Exception ex
      (swap! (:env ctx) update-in [:namespaces 'clojure.core]
             (fn [core]
               (assoc core '*e ex)))
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
          sci-ns (if ns-str
                   (sci-utils/namespace-object (:env ctx) (symbol ns-str) nil false)
                   (sci-utils/namespace-object (:env ctx) 'user nil false))]
      (sci/binding [vars/current-ns sci-ns]
        (let [
              ;;ns-sym (symbol ns)
              query (:symbol msg)
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

;; GraalVM doesn't support the .stop method on Threads, so for now we will have to live without interrupt
#_(defn interrupt [_ctx os msg threads]
  (let [session (get msg :session "none")
        id (get msg :interrupt-id)]
    (when-let [t (get @threads [session id])]
      (when @dev? (println "Killing thread" (str session "-" id)))
      (try (.stop ^java.lang.Thread t)
           (catch Throwable e
             (println e))))
    (send os (response-for msg {"status" #{"done"}}))))

(defn read-msg [msg]
  (-> (zipmap (map keyword (keys msg))
              (map #(if (bytes? %)
                      (String. (bytes %))
                      %) (vals msg)))
      (update :op keyword)))

(defn session-loop [ctx ^InputStream is os id #_threads]
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
                   (send os (response-for msg {"new-session" id "status" #{"done"}}))
                   (recur ctx is os id #_threads)))
        :eval (do
                (eval-msg ctx os msg #_threads)
                (recur ctx is os id #_threads))
        :load-file (let [file (:file msg)
                         msg (assoc msg :code file)]
                     (eval-msg ctx os msg #_threads)
                     (recur ctx is os id #_threads))
        :complete (do
                    (complete ctx os msg)
                    (recur ctx is os id #_threads))
        ;; :interrupt (do
        ;;              (interrupt ctx os msg threads)
        ;;              (recur ctx is os id threads))
        :describe
        (do (send os (response-for msg {"status" #{"done"}
                                        "aux" {}
                                        "ops" (zipmap #{"clone", "describe", "eval"}
                                                      (repeat {}))
                                        "versions" {} #_{"nrepl" {"major" "0"
                                                                  "minor" "4"
                                                                  "incremental" "0"
                                                                  "qualifier" ""}
                                                         "clojure"
                                                         {"*clojure-version*"
                                                          (zipmap (map name (keys *clojure-version*))
                                                                  (vals *clojure-version*))}}}))
            (recur ctx is os id #_threads))
        ;; fallback
        (do (when @dev?
              (println "Unhandled message" msg))
            (send os (response-for msg {"status" #{"error" "unknown-op" "done"}}))
            (recur ctx is os id #_threads))))))

(defn listen [ctx ^ServerSocket listener]
  (when @dev? (println "Listening"))
  (let [client-socket (.accept listener)
        in (.getInputStream client-socket)
        in (PushbackInputStream. in)
        out (.getOutputStream client-socket)
        #_threads #_(atom {})]
    (when @dev? (println "Connected."))
    (sci/future
      (sci/binding
          ;; allow *ns* to be set! inside future
          [vars/current-ns (vars/->SciNamespace 'user nil)
           sci/print-length @sci/print-length]
        (session-loop ctx in out "pre-init" #_threads)))
    (recur ctx listener)))


(def server (atom nil))

(defn stop-server! []
  (when-let [s @server]
    (.close ^ServerSocket s)
    (reset! server nil)))

(defn start-server! [ctx host+port]
  (vreset! dev? (= "true" (System/getenv "BABASHKA_DEV")))
  (let [parts (str/split host+port #":")
        [address port] (if (= 1 (count parts))
                         [nil (Integer. ^String (first parts))]
                         [(java.net.InetAddress/getByName (first parts))
                          (Integer. ^String (second parts))])
        host+port (if-not address (str "localhost:" port)
                          host+port)]
    #_(complete ctx nil {:symbol "json"})
    (println "Starting nREPL server at" host+port)
    (let [socket-server (new ServerSocket port 0 address)]
      (reset! server socket-server)
      (listen ctx socket-server))))
