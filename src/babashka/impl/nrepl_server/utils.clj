(ns babashka.impl.nrepl-server.utils
  {:no-doc true}
  (:refer-clojure :exclude [send])
  (:require [bencode.core :refer [write-bencode]])
  (:import [java.io Writer PrintWriter StringWriter OutputStream BufferedWriter]))

(set! *warn-on-reflection* true)

(def dev? (volatile! nil))

(defn response-for [old-msg msg]
  (let [session (get old-msg :session "none")
        id (get old-msg :id "unknown")]
    (assoc msg "session" session "id" id)))

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

;; from https://github.com/nrepl/nrepl/blob/1cc9baae631703c184894559a2232275dc50dff6/src/clojure/nrepl/middleware/print.clj#L63
(defn- to-char-array
  ^chars
  [x]
  (cond
    (string? x) (.toCharArray ^String x)
    (integer? x) (char-array [(char x)])
    :else x))

;; from https://github.com/nrepl/nrepl/blob/1cc9baae631703c184894559a2232275dc50dff6/src/clojure/nrepl/middleware/print.clj#L99
(defn replying-print-writer
  "Returns a `java.io.PrintWriter` suitable for binding as `*out*` or `*err*`. All
  of the content written to that `PrintWriter` will be sent as messages on the
  transport of `msg`, keyed by `key`."
  ^java.io.PrintWriter
  [o msg]
  (-> (proxy [Writer] []
        (write
          ([x]
           (let [cbuf (to-char-array x)]
             (.write ^Writer this cbuf (int 0) (count cbuf))))
          ([x off len]
           (let [cbuf (to-char-array x)
                 text (str (doto (StringWriter.)
                             (.write cbuf ^int off ^int len)))]
             (when (pos? (count text))
               (when @dev? (println "out str:" text))
               (send o (response-for msg {"out" text}))))))
        (flush [])
        (close []))
      (BufferedWriter. 1024)
      (PrintWriter. true)))
