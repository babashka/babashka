(ns nrepl.middleware.session
  "Support for persistent, cross-connection REPL sessions."
  {:author "Chas Emerick"}
  (:require
   clojure.main
   [nrepl.config :refer [config]]
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.middleware.interruptible-eval :refer [*msg*]]
   [nrepl.middleware.print :as print]
   [nrepl.misc :as misc :refer [uuid response-for log]]
   [nrepl.transport :as t]
   [nrepl.util.classloader :as classloader]
   [nrepl.util.threading :as threading])
  (:import
   #_(clojure.lang Compiler LineNumberingPushbackReader) ;; BB-PATCH the image has no Compiler
(clojure.lang LineNumberingPushbackReader)
   (java.util.concurrent Executors ExecutorService LinkedBlockingQueue)
   (nrepl SessionThread)
   (nrepl.in QueuePollingReader)))

(def ^:private sessions (atom {}))

(defn ^{:deprecated "1.4.0"} close-all-sessions!
  "Use this fn to manually shut down all sessions. Since each new session spawns
   a new thread, and sessions need to be otherwise explicitly closed, we can
   accumulate too many active sessions for the JVM. This occurs when we are
   running tests in watch mode."
  []
  (run! (fn [[id session]]
          (when-let [close (:close (meta session))]
            (close))
          (swap! sessions dissoc id))
        @sessions))

;; TODO: the way this is currently, :out and :err will continue to be
;; associated with a particular *msg* (and session) even when produced from a future,
;; agent, etc. due to binding conveyance.  This may or may not be desirable
;; depending upon the expectations of the client/user.  I'm not sure at the moment
;; how best to make it configurable though...

(def ^:dynamic ^:private *skipping-eol* false)

(def ephemeral-executor
  "Executor for running eval requests in ephemeral sessions."
  (delay (Executors/newCachedThreadPool
          (threading/thread-factory "nREPL-ephemeral-session-%d"))))

(defn- session-in
  "Returns a LineNumberingPushbackReader suitable for binding to *in*.
   When something attempts to read from it, it will (if empty) send a
   {:status :need-input} message on the provided transport so the client/user
   can provide content to be read."
  [session-id transport]
  (let [input-queue (LinkedBlockingQueue.)
        request-input #(or (.poll input-queue)
                           ;; Skipping newlines shouldn't trigger need-input
                           ;; from the client if queue is empty.
                           (when-not *skipping-eol*
                             (t/send transport
                                     (response-for *msg* :session session-id
                                                   :status :need-input))
                             (.take input-queue)))
        reader (LineNumberingPushbackReader.
                (QueuePollingReader. input-queue request-input))]
    {:input-queue input-queue
     :stdin-reader reader}))

(let [state (atom {})]
  (defn- dynvar-defaults-from-config []
    ;; Use simple one-value cache if config hasn't changed (config can currently
    ;; only change in tests).
    (second
     (if (identical? (first @state) config)
       @state
       (let [m (when-some [dynvars-map (:dynamic-vars config)]
                 (if-not (map? dynvars-map)
                   (log ":dynamic-vars should be a map")
                   (reduce-kv (fn [m k default]
                                (let [var (try (resolve k) (catch Exception _))]
                                  (if (var? var)
                                    (assoc m var default)
                                    (do (log "Could not resolve var:" (str k))
                                        m))))
                              {} dynvars-map)))]
         (reset! state [config m]))))))

(defn- gather-initial-bindings
  "Return a map of dynamic vars that have to be established for the underlying
  `interruptible-eval` middleware. This should be called when creating a session
  from scratch. The bound vars in this map can be reused across messages within
  one session."
  [_msg]
  (as-> (transient
         ;; Unconditional clojure.main/with-bindings bindings
         {#'*warn-on-reflection*     *warn-on-reflection*
          #'*math-context*           *math-context*
          #'*print-meta*             *print-meta*
          #'*print-length*           *print-length*
          #'*print-level*            *print-level*
          #'*data-readers*           *data-readers*
          #'*default-data-reader-fn* *default-data-reader-fn*
          #'*compile-path*           (System/getProperty "clojure.compile.path" "classes")
          #'*command-line-args*      *command-line-args*
          #'*unchecked-math*         *unchecked-math*
          #'*assert*                 *assert*
          #'*1                       nil
          #'*2                       nil
          #'*3                       nil
          #'*e                       nil
          #'*read-eval*              *read-eval*})
        m

    ;; Conditional bindings for compatibility with older Clojure.
    (let [print-ns-maps (resolve '*print-namespace-maps*)
          explain-out (resolve 'clojure.spec.alpha/*explain-out*)
          repl (resolve '*repl*)]
      (cond-> m
        print-ns-maps (assoc! print-ns-maps true)
        explain-out   (assoc! explain-out (var-get explain-out))
        repl          (assoc! repl true)))

    ;; This may contain other bindings established when
    ;; `nrepl.server/start-server` was called.
    (reduce conj! m (get-thread-bindings))

    ;; Bindings required for other nREPL middleware to work.
    (reduce (fn [m v] (assoc! m v @v)) m @@#'nrepl.middleware/per-session-dynvars)

    ;; Dynamic var defaults specified in the server config file.
    (reduce conj! m (dynvar-defaults-from-config))

    (persistent! m)))

#_(defn- add-per-message-bindings
  "Add dynamic bindings to `bindings-map` that must be rebound for each message."
  [{:keys [session file out-limit] :as msg} bindings-map]
  (let [;; *out* and *err* must be rebound on each new message.
        ;; TODO: out-limit -> out-buffer-size | err-buffer-size
        ;; TODO: new options: out-quota | err-quota
        opts {::print/buffer-size (or out-limit (get (meta session) :out-limit))}
        out (print/replying-PrintWriter :out msg opts)
        err (print/replying-PrintWriter :err msg opts)]
    (-> bindings-map
        (assoc #'*msg* msg
               Compiler/LOADER (classloader/dynamic-classloader)
               #'*out* out
               #'*err* err
               ;; clojure.test captures *out* at load-time, so we need to make
               ;; sure runtime output of test status/results is redirected
               ;; properly. There might be more cases like this, but we can't do
               ;; much about it besides patching like this. We intentionally
               ;; don't require beforehand in order to not add to loading times.
               (resolve 'clojure.test/*test-out*) out)
        (cond->
         file (assoc #'*file* file))))) ;; BB-PATCH no classloader binding in sci
(defn- add-per-message-bindings
  "Add dynamic bindings to `bindings-map` that must be rebound for each message."
  [{:keys [session file out-limit] :as msg} bindings-map]
  (let [opts {::print/buffer-size (or out-limit (get (meta session) :out-limit))}
        out (print/replying-PrintWriter :out msg opts)
        err (print/replying-PrintWriter :err msg opts)]
    (-> bindings-map
        (assoc #'*msg* msg
               #'*out* out
               #'*err* err
               (resolve 'clojure.test/*test-out*) out)
        (cond->
         file (assoc #'*file* file)))))

(defn make-ephemeral-exec-fn
  "Return an exec function that should be attached to ephemeral sessions.
  Ephemeral exec fn does not persist dynamic bindings and uses
  `ephemeral-executor`. The submitted task is made of:

  - an id (typically the message id)
  - thunk, a Runnable, the task itself
  - ack, another Runnable, ran to notify of successful execution of thunk"
  [session msg]
  (fn [_id ^Runnable thunk ^Runnable ack & [explicit-msg]]
    (let [f #(let [bindings (add-per-message-bindings (or explicit-msg msg)
                                                      @session)]
               (push-thread-bindings bindings)
               (try (.run thunk)
                    (some-> ack .run)
                    (finally (pop-thread-bindings))))]
      (.submit ^ExecutorService @ephemeral-executor ^Runnable f))))

(defn- create-session
  "Return a new session atom that contains a map of dynamic variables needed for
  `interruptible-eval`. If `session` is present in the message, base the new
  session on it, otherwise make one from scratch. The returned session is always
  an ephemeral one, with an ephemeral `:exec` function. To make it persistent,
  `register-session` has to be called next."
  ([{:keys [transport session out-limit] :as msg}]
   (let [id (uuid)
         {:keys [input-queue stdin-reader]} (session-in id transport)
         new-session (atom (assoc (if session
                                    @session
                                    (gather-initial-bindings msg))
                                  #'*in* stdin-reader
                                  #'*ns* (create-ns 'user))
                           :meta {:id id
                                  :out-limit (or out-limit (:out-limit (meta session)))
                                  :stdin-reader stdin-reader
                                  :input-queue input-queue})]
     (alter-meta! new-session assoc :exec (make-ephemeral-exec-fn new-session msg))
     new-session)))

(defn session-exec
  "Takes a session and returns a map of three functions:
  - `:exec` - takes an id (typically a msg-id), a thunk and an ack runnables.
    Only thunk can be interrupted. Executions are serialized and occur on a
    single thread.
  - `:interrupt` - takes an id and tries to interrupt the matching execution
    (submitted with :exec above). A nil id is meant to match the currently
    running execution. The return value can be either: `:idle` (no running
    execution), the interrupted id, or nil when the running id doesn't match the
    id argument. Upon successful interruption the backing thread is replaced.
  - `:close` - terminates the backing thread."
  [session]
  (let [id (:id (meta session))
        state (atom nil)
        session-loop
        #(try
           (loop []
             (let [{:keys [^LinkedBlockingQueue queue]} @state
                   [exec-id ^Runnable r ^Runnable ack msg] (.take queue)
                   exec-id (or exec-id (uuid)) ;; :id may be absent in msg.
                   bindings (add-per-message-bindings msg @session)]
               (swap! state assoc :running exec-id)
               (push-thread-bindings bindings)
               (if (fn? r)
                 (r) ;; -1 stack frame this way.
                 (.run r))
               (swap! session (fn [current]
                                (-> (merge current (get-thread-bindings))
                                    ;; Remove vars that we don't want to save
                                    ;; into the session.
                                    #_(dissoc #'*msg* Compiler/LOADER) ;; BB-PATCH no classloader binding in sci
(dissoc #'*msg*))))
               ;; We don't use try/finally here because if the eval throws an
               ;; exception, we're going to discard the whole thread. This makes
               ;; the stack cleaner.
               (pop-thread-bindings)
               (let [state-d @state]
                 (when (and (= (:running state-d) exec-id)
                            (compare-and-set! state state-d
                                              (assoc state-d :running nil)))
                   (some-> ack .run)
                   (recur)))))
           (catch InterruptedException _e))
        reset-state
        #(let [thread (SessionThread. session-loop (str "nREPL-session-" id)
                                      (classloader/dynamic-classloader))]
           (reset! state {:queue (LinkedBlockingQueue.)
                          :running nil
                          :thread thread})
           (.start thread))]
    (reset-state)
    ;; This map is added to the meta of the session object by `register-session`,
    ;; it contains functions that are accessed by `interrupt-session` and `close-session`.
    {:interrupt (fn [exec-id]
                  ;; nil means interrupt whatever is running
                  ;; returns :idle, interrupted id or nil
                  (let [{:keys [running thread] :as state-d} @state]
                    (cond
                      (nil? running) :idle
                      (and (or (nil? exec-id) (= running exec-id))
                           (compare-and-set! state state-d
                                             (assoc state-d :running nil)))
                      (do
                        (threading/interrupt-stop thread)
                        (reset-state)
                        running))))
     :close #(threading/interrupt-stop (:thread @state))
     ;; :exec has two arities — one with explicit msg argument (proper) and one
     ;; without (for compatibility). The compat arity takes message from *msg*
     ;; dynvar which isn't correct if some middleware modifies the message
     ;; before calling :exec (see https://github.com/nrepl/nrepl/issues/363).
     :exec (fn this
             ([exec-id r ack]
              ;; Here, *msg* is bound by session middleware on the server/handler
              ;; thread. We have to convey it to the executor thread.
              (if *msg*
                (this exec-id r ack *msg*)
                (log "*msg* is unbound in a persistent session.")))
             ([exec-id r ack msg]
              (.put ^LinkedBlockingQueue (:queue @state) [exec-id r ack msg])))}))

(defn- register-session
  "Registers a new session containing the baseline bindings contained in the
   given message's :session."
  [msg]
  (let [session (create-session msg)
        {:keys [id]} (meta session)]
    (alter-meta! session into (session-exec session))
    (swap! sessions assoc id session)
    (t/respond-to msg :status :done, :new-session id)))

(defn- interrupt-session
  [{:keys [session interrupt-id transport] :as msg}]
  (let [{:keys [interrupt] session-id :id} (meta session)
        interrupted-id (when interrupt (interrupt interrupt-id))]
    (cond
      (nil? interrupt)
      (t/respond-to msg :status #{:error :session-ephemeral :done})

      (nil? interrupted-id)
      (t/respond-to msg :status #{:error :interrupt-id-mismatch :done})

      (= :idle interrupted-id)
      (t/respond-to msg :status #{:session-idle :done})

      :else
      (do
        (t/send transport {:status #{:interrupted :done}
                           :id interrupted-id
                           :session session-id})
        (t/respond-to msg :status #{:done})))))

(defn close-session
  "Close the given session."
  [session]
  (let [{:keys [close] session-id :id} (meta session)]
    (when close (close))
    (swap! sessions dissoc session-id)))

(defn- handle-session-close
  "Close the session associated with the given message and notify the user."
  [{:keys [session] :as msg}]
  (close-session session)
  (t/respond-to msg :status #{:done :session-closed}))

(defn session
  "Session middleware.  Returns a handler which supports these :op-erations:

   * \"clone\", which will cause a new session to be retained.  The ID of this
     new session will be returned in a response message in a :new-session
     slot.  The new session's state (dynamic scope, etc) will be a copy of
     the state of the session identified in the :session slot of the request.
   * \"interrupt\", which will attempt to interrupt the current execution with
     id provided in the :interrupt-id slot.
   * \"close\", which drops the session indicated by the
     ID in the :session slot.  The response message's :status will include
     :session-closed.
   * \"ls-sessions\", which results in a response message
     containing a list of the IDs of the currently-retained sessions in a
     :session slot.

   Messages indicating other operations are delegated to the given handler,
   with the session identified by the :session ID added to the message. If
   no :session ID is found, a new session is created (which will only
   persist for the duration of the handling of the given message).

   Requires the interruptible-eval middleware (specifically, its binding of
   *msg* to the currently-evaluated message so that session-specific *out*
   and *err* content can be associated with the originating message)."
  [h]
  (fn [{:keys [op session] :as msg}]
    (let [the-session (if session
                        (@sessions session)
                        (create-session msg))]
      (if-not the-session
        (t/respond-to msg :status #{:error :unknown-session :done})
        (let [msg (assoc msg :session the-session)]
          (case op
            "clone" (register-session msg)
            "interrupt" (interrupt-session msg)
            "close" (handle-session-close msg)
            "ls-sessions" (t/respond-to msg {:sessions (or (keys @sessions) [])
                                             :status   :done})
            (binding [*msg* msg]
              ;; Bind *msg* so it can later be accessed by persistent session
              ;; functions like session-exec.
              (h msg))))))))

(set-descriptor! #'session
                 {:requires #{}
                  :expects #{}
                  :describe-fn (fn [{:keys [session]}]
                                 (when (instance? clojure.lang.Atom session)
                                   {:current-ns (-> @session (get #'*ns*) str)}))
                  :handles {"clone"
                            {:doc "Clones the current session, returning the ID of the newly-created session."
                             :requires {}
                             :optional {"session" "The ID of the session to be cloned; if not provided, a new session with default bindings is created, and mapped to the returned session ID."
                                        "client-name" "The nREPL client name. e.g. \"CIDER\""
                                        "client-version" "The nREPL client version. e.g. \"1.2.3\""}
                             :returns {"new-session" "The ID of the new session."}}
                            "interrupt"
                            {:doc "Attempts to interrupt some executing request. When interruption succeeds, the thread used for execution is killed, and a new thread spawned for the session. While the session middleware ensures that Clojure dynamic bindings are preserved, other ThreadLocals are not. Hence, when running code intimately tied to the current thread identity, it is best to avoid interruptions. On Java 20 and later, if `-Djdk.attach.allowAttachSelf` is enabled, the JVMTI agent will be used to attempt to stop the thread."
                             :requires {"session" "The ID of the session used to start the request to be interrupted."}
                             :optional {"interrupt-id" "The opaque message ID sent with the request to be interrupted."}
                             :returns {"status" "'interrupted' if a request was identified and interruption will be attempted
'session-idle' if the session is not currently executing any request
'interrupt-id-mismatch' if the session is currently executing a request sent using a different ID than specified by the \"interrupt-id\" value
'session-ephemeral' if the session is an ephemeral session"}}
                            "close"
                            {:doc "Closes the specified session."
                             :requires {"session" "The ID of the session to be closed."}
                             :optional {}
                             :returns {}}
                            "ls-sessions"
                            {:doc "Lists the IDs of all active sessions."
                             :requires {}
                             :optional {}
                             :returns {"sessions" "A list of all available session IDs."}}}})

(defn add-stdin
  "Stdin middleware. Handles \"stdin\" :op-eration, which adds content provided in
  a :stdin slot to the session's *in* Reader. Requires the session middleware."
  [h]
  (fn [{:keys [op stdin session] :as msg}]
    ;; NB: confusing hack. When `(read)` is issued to an input stream, it
    ;; returns a Lisp form, ending with the last character of the form,
    ;; naturally (e.g. a closing paren). However, it is expected that any
    ;; trailing newline after such form is thrown away in order for a
    ;; subsequent `(read-line)` call to not just return that empty newline but
    ;; the actual following content. To simulate this behavior, we run
    ;; newline-skipping function before every `eval` request.
    (when (= op "eval")
      (let [in (:stdin-reader (meta session))]
        (binding [*skipping-eol* true]
          (clojure.main/skip-if-eol in))))

    (if (= op "stdin")
      (let [^LinkedBlockingQueue q (:input-queue (meta session))]
        (if (empty? stdin)
          #_(.put q -1) ;; BB-PATCH the EOF marker: QueuePollingReader compares with an Integer, a Long never equals it; fixed upstream in nrepl#470, after 1.7.0
(.put q (int -1))
          (.addAll q (seq stdin)))
        (t/respond-to msg :status :done))
      (h msg))))

(set-descriptor! #'add-stdin
                 {:requires #{#'session}
                  :expects #{"eval"}
                  :handles {"stdin"
                            {:doc "Add content from the value of \"stdin\" to *in* in the current session."
                             :requires {"stdin" "Content to add to *in*."}
                             :optional {}
                             :returns {"status" "A status of \"need-input\" will be sent if a session's *in* requires content in order to satisfy an attempted read operation."}}}})
