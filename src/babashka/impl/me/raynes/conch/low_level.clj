;; From https://github.com/clj-commons/conch

(ns babashka.impl.me.raynes.conch.low-level
  "A simple but flexible library for shelling out from Clojure."
  {:no-doc true}
  (:refer-clojure :exclude [flush read-line])
  (:require [clojure.java.io :as io])
  (:import [java.util.concurrent TimeUnit TimeoutException]
           [java.io InputStream OutputStream]))

(set! *warn-on-reflection* true)

(defn proc
  "Spin off another process. Returns the process's input stream,
  output stream, and err stream as a map of :in, :out, and :err keys
  If passed the optional :dir and/or :env keyword options, the dir
  and enviroment will be set to what you specify. If you pass
  :verbose and it is true, commands will be printed. If it is set to
  :very, environment variables passed, dir, and the command will be
  printed. If passed the :clear-env keyword option, then the process
  will not inherit its environment from its parent process."
  [& args]
  (let [[cmd args] (split-with (complement keyword?) args)
        args (apply hash-map args)
        builder (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String cmd))
        env (.environment builder)]
    (when (:clear-env args)
      (.clear env))
    (doseq [[k v] (:env args)]
      (.put env k v))
    (when-let [dir (:dir args)]
      (.directory builder (io/file dir)))
    (when (:verbose args) (apply println cmd))
    (when (= :very (:verbose args))
      (when-let [env (:env args)] (prn env))
      (when-let [dir (:dir args)] (prn dir)))
    (when (:redirect-err args)
      (.redirectErrorStream builder true))
    (let [process (.start builder)]
      {:out (.getInputStream process)
       :in  (.getOutputStream process)
       :err (.getErrorStream process)
       :process process})))

(defn destroy
  "Destroy a process."
  [process]
  (.destroy ^Process (:process process)))

;; .waitFor returns the exit code. This makes this function useful for
;; both getting an exit code and stopping the thread until a process
;; terminates.
(defn exit-code
  "Waits for the process to terminate (blocking the thread) and returns
   the exit code. If timeout is passed, it is assumed to be milliseconds
   to wait for the process to exit. If it does not exit in time, it is
   killed (with or without fire)."
  ([process] (.waitFor ^Process (:process process)))
  ([process timeout]
   (try
     (let [^java.util.concurrent.Future fut
           (future (.waitFor ^Process (:process process)))]
       (.get fut timeout TimeUnit/MILLISECONDS))
     (catch Exception e
       (if (or (instance? TimeoutException e)
               (instance? TimeoutException (.getCause e)))
         (do (destroy process)
             :timeout)
         (throw e))))))

(defn flush
  "Flush the output stream of a process."
  [process]
  (let [^OutputStream in (:in process)]
    (.flush in)))

(defn done
  "Close the process's output stream (sending EOF)."
  [proc]
  (let [^OutputStream in (:in proc)]
    (.close in)))

(defn stream-to
  "Stream :out or :err from a process to an ouput stream.
  Options passed are fed to clojure.java.io/copy. They are :encoding to
  set the encoding and :buffer-size to set the size of the buffer.
  :encoding defaults to UTF-8 and :buffer-size to 1024."
  [process from to & args]
  (apply io/copy (process from) to args))

(defn feed-from
  "Feed to a process's input stream with optional. Options passed are
  fed to clojure.java.io/copy. They are :encoding to set the encoding
  and :buffer-size to set the size of the buffer. :encoding defaults to
  UTF-8 and :buffer-size to 1024. If :flush is specified and is false,
  the process will be flushed after writing."
  [process from & {flush? :flush :or {flush? true} :as all}]
  (apply io/copy from (:in process) all)
  (when flush? (flush process)))

(defn stream-to-string
  "Streams the output of the process to a string and returns it."
  [process from & args]
  (with-open [writer (java.io.StringWriter.)]
    (apply stream-to process from writer args)
    (str writer)))

;; The writer that Clojure wraps System/out in for *out* seems to buffer
;; things instead of writing them immediately. This wont work if you
;; really want to stream stuff, so we'll just skip it and throw our data
;; directly at System/out.
(defn stream-to-out
  "Streams the output of the process to System/out"
  [process from & args]
  (apply stream-to process from (System/out) args))

(defn feed-from-string
  "Feed the process some data from a string."
  [process s & args]
  (apply feed-from process (java.io.StringReader. s) args))

(defn read-line
  "Read a line from a process' :out or :err."
  [process from]
  (binding [*in* (io/reader (from process))]
    (clojure.core/read-line)))
