(ns babashka.impl.nrepl-server
  {:no-doc true}
  (:refer-clojure :exclude [send future binding])
  (:require [babashka.impl.bencode.core :refer [write-bencode read-bencode]]
            [clojure.string :as str]
            [clojure.template :refer [apply-template]]
            [sci.core :as sci]
            [sci.impl.interpreter :refer [eval-string*]]
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


;; err:"Execution error (ArithmeticException) at user/eval11906 (form-init7923941828443507176.clj:1).
;; Divide by zero
;; "
;; id:"9"
;; session:"e15078b4-14b4-4f01-87e6-71f77d233e38"


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

(defn eval-msg [ctx o msg]
  (let [code-str (get msg :code)
        sw (StringWriter.)
        value (if (str/blank? code-str)
                ::nil
                (sci/binding [sci/out sw] (eval-string* ctx code-str)))
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
    (send o (response-for msg {"status" #{"done"}}))))

(defn fully-qualified-syms [ctx ns-sym]
  (let [syms (eval-string* ctx (format "(keys (ns-map '%s))" ns-sym))
        sym-strs (map #(str "`" %) syms)
        sym-expr (str "[" (str/join " " sym-strs) "]")
        syms (eval-string* ctx sym-expr)]
    syms))

(defn sym-vecs [syms]
  (map (fn [sym]
         [(namespace sym) (name sym)])
       syms))

(defn match [alias->ns ns->alias query-ns query-name sym-ns sym-name]
  #_(when (and sym-ns (not= "clojure.core" sym-ns))
    (prn query-ns query-name sym-ns sym-name))
  (let [name-pat (re-pattern query-name)]
    (if query-ns
      (if-let [matching-ns (when (and query-ns sym-ns (= sym-ns query-ns))
                             query-ns)]
        (when (re-find name-pat sym-name)
          [matching-ns sym-name])
        (when-let [matching-alias (when (and query-ns sym-ns
                                             (= sym-ns (when-let [v (get alias->ns (symbol query-ns))]
                                                         (str v))))
                                    query-ns)]
          (when (re-find name-pat sym-name)
            [matching-alias sym-name])))
      (if (re-find name-pat sym-name)
        [sym-ns sym-name]
        (when sym-ns
          (if (re-find name-pat sym-ns)
            [sym-ns sym-name]
            (when-let [v (get ns->alias (symbol sym-ns))]
              (let [alias-str (str v)]
                (when (re-find name-pat alias-str)
                  [alias-str sym-name])))))))))

(defn complete [ctx o msg]
  (try (let [;; ns (:ns msg)
             ;;ns-sym (symbol ns)
             query (:symbol msg)
             from-current-ns (fully-qualified-syms ctx (eval-string* ctx "(ns-name *ns*)"))
             alias->ns (eval-string* ctx "(let [m (ns-aliases *ns*)] (zipmap (keys m) (map ns-name (vals m))))")
             ns->alias (zipmap (vals alias->ns) (keys alias->ns))
             from-aliased-nss (doall (mapcat
                                      (fn [alias]
                                        (let [ns (get alias->ns alias)
                                              syms (eval-string* ctx (format "(keys (ns-publics '%s))" ns))]
                                          (map (fn [sym]
                                                 [(str ns) (str sym)])
                                               syms)))
                                      (keys alias->ns)))
             svs (concat (sym-vecs from-current-ns) from-aliased-nss)
             parts (str/split query #"/")
             [query-ns query-name] (if (= 1 (count parts))
                                     [nil (first parts)]
                                     parts)
             completions (doall (keep (fn [[sym-ns sym-name]]
                                        (match alias->ns ns->alias query-ns query-name sym-ns sym-name))
                                      svs))
             completions (mapv (fn [[namespace name]]
                                 {"candidate" (str name) "ns" (str namespace) #_"type" #_"function"})
                               completions)]
         (send o (response-for msg {"completions" completions
                                      "status" #{"done"}})))
       (catch Throwable e
         (println e)
         (send o (response-for msg {"completions" []
                                      "status" #{"done"}})))))

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
                   (send os (response-for msg {"new-session" id "status" #{"done"}}))
                   (recur ctx is os id)))
        :eval (do
                (try (eval-msg ctx os msg)
                     (catch Exception ex
                       (swap! (:env ctx) update-in [:namespaces 'clojure.core]
                              (fn [core]
                                (assoc core '*e ex)))
                       (send-exception os msg ex)))
                (recur ctx is os id))
        :load-file (do
                     (let [file (:file msg)
                           msg (assoc msg :code file)]
                       (try (eval-msg ctx os msg)
                            (catch Exception ex
                              (swap! (:env ctx) update-in [:namespaces 'clojure.core]
                                     (fn [core]
                                       (assoc core '*e ex)))
                              (send-exception os msg ex))))
                     (recur ctx is os id))
        :complete (do
                    (complete ctx os msg)
                    (recur ctx is os id))
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
        out (.getOutputStream client-socket)]
    (when @dev? (println "Connected."))
    (sci/future
      (sci/binding
          ;; allow *ns* to be set! inside future
          [vars/current-ns (vars/->SciNamespace 'user nil)
           sci/print-length @sci/print-length]
        (session-loop ctx in out "pre-init")))
    (recur ctx listener)))

(defn start-server! [ctx host+port]
  (vreset! dev? (= "true" (System/getenv "BABASHKA_DEV")))
  (let [parts (str/split host+port #":")
        [address port] (if (= 1 (count parts))
                         [nil (Integer. ^String (first parts))]
                         [(first parts) (Integer. ^String (second parts))])
        host+port (if-not address (str "localhost:" port)
                          host+port)]
    #_(complete ctx nil {:symbol "json"})
    (println "Starting nREPL at" host+port)
    (listen ctx (new ServerSocket port 0 address))))
