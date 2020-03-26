(ns babashka.impl.nrepl-server
  {:no-doc true}
  (:refer-clojure :exclude [send future binding])
  (:require [babashka.impl.bencode.core :refer [write-bencode read-bencode]]
            [clojure.string :as str]
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
  (when @dev? (prn "Sending" msg))
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
    (when @dev? (prn "sending ex" ex-name))
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
        out-str (not-empty (str sw))]
    (when @dev? (println "out str:" out-str))
    (send o (response-for msg (cond-> {"ns" (vars/current-ns-name)}
                                out-str (assoc "value" out-str))))
    (send o (response-for msg (cond-> {"ns" (vars/current-ns-name)}
                                (not (identical? value ::nil)) (assoc "value" (pr-str value)))))
    (send o (response-for msg {"status" #{"done"}}))))

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
                     (catch Exception exn
                       (send-exception os msg exn)))
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
    (println "Starting nREPL at" host+port)
    (listen ctx (new ServerSocket port 0 address))))
