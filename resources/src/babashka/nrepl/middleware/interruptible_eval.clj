(ns nrepl.middleware.interruptible-eval
  "Supports the ability to evaluate code. The name of the middleware is
  slightly misleading, as interrupt is currently supported at a session level
  but the name is retained for backwards compatibility."
  {:author "Chas Emerick"}
  (:refer-clojure :exclude [read])
  (:require
   [clojure.java.io :as io]
   clojure.main
   clojure.test
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.middleware.caught :as caught]
   [nrepl.middleware.print :as print]
   [nrepl.transport :as t])
  (:import
   #_(clojure.lang Compiler$CompilerException
                 LineNumberingPushbackReader LispReader$ReaderException) ;; BB-PATCH the image has no Compiler or LispReader
(clojure.lang LineNumberingPushbackReader)
   (java.io PushbackReader StringReader Writer)
   #_(java.lang.reflect Field) ;; BB-PATCH no reflection on the reader's column field
))

(def ^:dynamic *msg*
  "The message currently being evaluated."
  nil)

(defn- set-line!
  [^LineNumberingPushbackReader reader line]
  (-> reader (.setLineNumber line)))

#_(defn- set-column!
  [^LineNumberingPushbackReader reader column]
  (when-let [field (->> LineNumberingPushbackReader
                        (.getDeclaredFields)
                        (filter #(= "_columnNumber" (.getName ^Field %)))
                        first)]
    (-> ^Field field
        (doto (.setAccessible true))
        (.set reader column)))) ;; BB-PATCH the column field is set through reflection
(defn- set-column! [_reader _column] nil)

(defn- source-logging-pushback-reader
  [code line column]
  (let [reader (LineNumberingPushbackReader. (StringReader. code))]
    (when line (set-line! reader (int line)))
    (when column (set-column! reader (int column)))
    reader))

(defn- interrupted?
  "Returns true if the given throwable was ultimately caused by an interrupt."
  [^Throwable e]
  #_(or (instance? ThreadDeath (clojure.main/root-cause e))
      (and (instance? Compiler$CompilerException e)
           (instance? ThreadDeath (.getCause e)))) ;; BB-PATCH no CompilerException in sci
(instance? ThreadDeath (clojure.main/root-cause e)))

(defn- short-file-name [source-path]
  (try (.getName (io/file source-path))
       (catch Exception _)))

(defn evaluator
  "Return a closure that evaluates msg's `:code` (either a string or a seq of
  forms to be evaluated) within the dynamic context of its session. `:ns` can be
  optionally specified (resolved via `find-ns`). The message MUST contain a
  Transport implementation in :transport; expression results and errors will be
  sent via that Transport."
  [msg]
  (fn run []
    (let [{:keys [eval ns code file file-name line column]} msg
          ;; Qualified read-fn and eval-fn are a way for middleware to provide
          ;; custom read and eval functions which can be runtime closures.
          ;; `:eval` parameter that can come from a client can only be a symbol
          ;; pointing to a var.
          read-fn (or (::read-fn msg) clojure.core/read)
          eval-fn (or (::eval-fn msg) (some-> eval symbol find-var))
          ;; Needed exclusively by `load-file` middleware to say that if error
          ;; happens anywhere during the evaluation of the file, don't try
          ;; evaluating subsequent forms.
          stop-on-error? (::stop-on-error msg)
          ;; A way to provide extra temporary bindings (not session-persisted).
          bindings (or (::bindings msg) {})
          file-name (or file-name (short-file-name file))
          explicit-ns (some-> ns symbol find-ns)
          eof (Object.)
          errored? (volatile! false)
          read-cond (keyword (:read-cond msg :allow))
          read (cond (string? code)
                     (let [reader (source-logging-pushback-reader code line column)]
                       #(read-fn {:read-cond read-cond :eof eof} reader))

                     ;; Special case, only used for TTY transport.
                     (instance? PushbackReader code)
                     (let [already-read? (volatile! false)]
                       ;; For TTY, only allow one read per eval, then
                       ;; return EOF.
                       #(if @already-read?
                          eof
                          (do (vreset! already-read? true)
                              (read-fn {:read-cond read-cond :eof eof} code))))

                     (instance? Iterable code)
                     (let [code (.iterator ^Iterable code)]
                       #(if (.hasNext code)
                          (.next code)
                          eof)))
          caught (fn [^Throwable e]
                   (set! *e e)
                   (vreset! errored? true)
                   (when-not (interrupted? e)
                     (t/respond-to msg {::caught/throwable e
                                        :status #{:eval-error}
                                        :ex #_(str (class e)) ;; BB-PATCH the class of the exception sci's error wraps
(str (class (if (= :sci/error (:type (ex-data e))) (or (ex-cause e) e) e)))
                                        :root-ex (str (class (clojure.main/root-cause e)))})))]
      (push-thread-bindings (merge (when explicit-ns {#'*ns* explicit-ns})
                                   (when (and file file-name)
                                     #_{Compiler/SOURCE_PATH file
                                      Compiler/SOURCE file-name} ;; BB-PATCH sci reads the source path from *file*
{#'*file* file})
                                   bindings))
      (try
        (loop []
          (let [input (try
                        (clojure.main/with-read-known (read))
                        (catch Throwable e
                          (let [e (if #_(instance? LispReader$ReaderException e) ;; BB-PATCH sci reader errors are ex-info
(= :sci.error/parse (:type (ex-data e)))
                                    (ex-info nil {:clojure.error/phase :read-source} e)
                                    e)]
                            ;; If error happens during read phase, call
                            ;; caught-hook but don't continue executing.
                            (caught e)
                            eof)))]
            (when-not (identical? input eof)
              (try
                (let [value (if eval-fn
                              (eval-fn input)
                              ;; If eval-fn is not provided, call
                              ;; Compiler/eval directly for slimmer stack.
                              #_(Compiler/eval input true) ;; BB-PATCH sci eval, eval is shadowed by the message key
(clojure.core/eval input))]
                  (set! *3 *2)
                  (set! *2 *1)
                  (set! *1 value)
                  (try
                    ;; *out* has :tag metadata; *err* does not
                    (.flush ^Writer *err*)
                    (.flush *out*)
                    (t/respond-to msg {:ns (str (ns-name *ns*))
                                       :value value
                                       ::print/keys #{:value}})
                    (catch Throwable e
                      (throw (ex-info nil {:clojure.error/phase :print-eval-result} e)))))
                #_(catch Throwable e
                  (caught e)) ;; BB-PATCH a :sci/error catch gets the exception with sci's callstack, for *e and the stacktrace ops
(catch ^{:sci/error true} Throwable e
                  (caught e)))
              ;; Otherwise, when errors happen during eval/print phase,
              ;; report the exception but continue executing the
              ;; remaining readable forms, unless we load the whole file.
              (when-not (and stop-on-error? @errored?)
                (recur)))))

        (catch Throwable e
          (caught e))
        (finally
          (flush)
          (pop-thread-bindings))))))

(defn interruptible-eval
  "Evaluation middleware that supports interrupts.  Returns a handler that supports
   \"eval\" and \"interrupt\" :op-erations that delegates to the given handler
   otherwise."
  [h & _configuration]
  (fn [{:keys [op session id ns code] :as msg}]
    (let [{:keys [exec]} (meta session)]
      (if (= op "eval")
        (cond (nil? code)
              (t/respond-to msg :status #{:error :no-code :done})

              (not (or (string? code) (instance? Iterable code)
                       (instance? PushbackReader code)))
              (t/respond-to msg :status #{:error :unknown-code-type :done})

              (and (some? ns) (nil? (some-> ns symbol find-ns)))
              (t/respond-to msg {:status #{:error :namespace-not-found :done}
                                 :ns     ns})

              :else (exec id
                          (evaluator msg)
                          #(t/respond-to msg :status :done)
                          msg))
        (h msg)))))

(set-descriptor! #'interruptible-eval
                 {:requires #{"clone" "close" #'caught/wrap-caught #'print/wrap-print}
                  :expects #{}
                  :handles {"eval"
                            {:doc "Evaluates code. Note that unlike regular stream-based Clojure REPLs, nREPL's `\"eval\"` short-circuits on first read error and will not try to read and execute the remaining code in the message."
                             :requires {"code" "The code to be evaluated."
                                        "session" "The ID of the session within which to evaluate the code."}
                             :optional (merge caught/wrap-caught-optional-arguments
                                              print/wrap-print-optional-arguments
                                              {"id" "An opaque message ID that will be included in responses related to the evaluation, and which may be used to restrict the scope of a later \"interrupt\" operation."
                                               "eval" "A fully-qualified symbol naming a var whose function value will be used to evaluate [code], instead of `clojure.core/eval` (the default)."
                                               "ns" "The namespace in which to perform the evaluation. The supplied namespace must exist already (e.g. be loaded). If no namespace is specified the evaluation falls back to `*ns*` for the session in question."
                                               "file" "The path to the file containing [code]. `clojure.core/*file*` will be bound to this."
                                               "file-name" "Name of source file, e.g. io.clj. Do not confuse with `file` param which contains full path while `file-name` is only the short file name. Both parameters are needed to properly set the bytecode location metadata."
                                               "line" "The line number in [file] at which [code] starts."
                                               "column" "The column number in [file] at which [code] starts."
                                               "read-cond" "The options passed to the reader before the evaluation. Useful when middleware in a higher layer wants to process reader conditionals."})
                             :returns {"ns" "*ns*, after successful evaluation of `code`."
                                       "value" "The result of evaluating `code`, often `read`able. This printing is provided by the `print` middleware. Superseded by `ex` and `root-ex` if an exception occurs during evaluation."
                                       "ex" "The type of exception thrown, if any. If present, then `:value` will be absent."
                                       "root-ex" "The type of the root exception thrown, if any. If present, then `:value` will be absent."}}}})
