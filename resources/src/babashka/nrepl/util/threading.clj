(ns nrepl.util.threading
  "Functions and tools for dealing with all threads and threadpools necessary for
  nREPL operation."
  {:added "1.3"}
  (:require
   [nrepl.misc :as misc]
   [nrepl.util.classloader :as classloader])
  (:import
   (java.util.concurrent Executors ExecutorService ScheduledExecutorService
                         TimeUnit)
   (nrepl DaemonThreadFactory)))

(defn thread-factory
  "Return a thread factory that produces daemon threads with the name specified by
  `thread-name-fmt`."
  [thread-name-fmt]
  (DaemonThreadFactory. thread-name-fmt (classloader/dynamic-classloader)))

(defmacro run-with
  "Run the provided `body` using `executor`. Don't wait for the result (unless the
  executor itself is blocking)."
  {:style/indent 1}
  [executor & body]
  `(.submit ~(with-meta executor {:tag (symbol (.getName ExecutorService))})
            (reify Runnable
              (run [_#]
                ~@body))))

;; Executors are wrapped in delays to defer their initialization.

(def listen-executor
  "Executor used to accept incoming connections."
  (delay (Executors/newCachedThreadPool (thread-factory "nREPL-server-%d"))))

(def transport-executor
  "Executor used to run the transport handler loop and handle individual requests."
  (delay (Executors/newCachedThreadPool (thread-factory "nREPL-transport-%d"))))

(def handle-executor
  "Executor used to run the handler loop and handle individual requests."
  (delay (Executors/newCachedThreadPool (thread-factory "nREPL-handler-%d"))))

;;;; Thread interruption. Used by session middleware to make eval interruptible.

(def thread-reaper-executor
  "Executor used to kill session threads that did not respond to interrupt."
  (delay (Executors/newScheduledThreadPool
          0 (thread-factory "nREPL-thread-reaper-%d"))))

(defn- jvmti-stop-thread [t]
  ((misc/requiring-resolve 'nrepl.util.jvmti/stop-thread) t))

(defn- try-stop-thread [^Thread t]
  (cond
    (<= misc/java-version 19) (.stop t)
    ;; Since JDK20, Thread.stop() no longer works. We must resort to using
    ;; JVMTI native agent which luckily still supports Stop Thread command.
    ;; Whether this is more dangerous than calling Thread.stop() in earlier
    ;; JDKs is unknown, but assume the worst and never use this if you can't
    ;; take the risk!
    (misc/jvmti-agent-enabled?) (jvmti-stop-thread t)

    (not (misc/attach-self-enabled?))
    (misc/log "Cannot stop thread on JDK21+ without -Djdk.attach.allowAttachSelf"
              "enabled, see https://nrepl.org/nrepl/installation.html#jvmti.")))

(def ^:private force-stop-delay-ms 5000)

(defn interrupt-stop
  "Try to interrupt the thread normally. Asynchronously wait for 5000ms and, if
  the thread didn't terminate itself cleanly, kill the thread using either
  `.stop` or JVMTI agent. This behaviour strikes a balance between allowing a
  thread to respond to an interrupt, but also ensuring we clean up runaway
  processes."
  [^Thread t]
  ;; TODO: make timeouts configurable?
  (.interrupt t)
  (.schedule ^ScheduledExecutorService @thread-reaper-executor
             ^Runnable #(misc/log-exceptions
                         (when-not (= (.getState t) Thread$State/TERMINATED)
                           (try-stop-thread t)))
             ^long force-stop-delay-ms TimeUnit/MILLISECONDS))
