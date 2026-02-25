(ns babashka.impl.logging
  (:require [clojure.tools.logging]
            [clojure.tools.logging.impl :as impl]
            [clojure.tools.logging.readable]
            [sci.core :as sci]
            [taoensso.encore :as enc :refer [have]]
            [taoensso.timbre :as timbre]
            [taoensso.truss :as truss]))

;;;; timbre

(def tns (sci/create-ns 'taoensso.timbre nil))

(defonce callsite-counter
  (enc/counter))

(defn get-source
  "Returns {:keys [ns line column file]} source location given a macro's
     compile-time `&form` and `&env` vals. See also `keep-callsite`."
  {:added "Encore v3.61.0 (2023-07-07)"}
  [macro-form _macro-env]
  (let [{:keys [line column]} (meta macro-form)
        file @sci/file]
    {:ns     (str @sci/ns)
     :line   line
     :column column
     :file file}))

(defmacro log! ; Public wrapper around `-log!`
  "Core low-level log macro. Useful for tooling/library authors, etc.

       * `level`    - must eval to a valid logging level
       * `msg-type` - must eval to e/o #{:p :f nil}
       * `args`     - arguments seq (ideally vec) for logging call
       * `opts`     - ks e/o #{:config ?err ?base-data spying?
                               :?ns-str :?file :?line :?column}

     Supports compile-time elision when compile-time const vals
     provided for `level` and/or `?ns-str`.

     Logging wrapper examples:

       (defn     log-wrapper-fn    [& args]                        (timbre/log! :info :p  args))
       (defmacro log-wrapper-macro [& args] (timbre/keep-callsite `(timbre/log! :info :p ~args)))"

  ([{:as   opts
     :keys [loc level msg-type args vargs
            config ?err ?base-data spying?
            #_instant #_may-log?]
     :or
     {config `timbre/*config*
      ?err   :auto}}]

   (truss/have? [:or nil? sequential? symbol?] args)
   (let [callsite-id (callsite-counter)
         loc-form (or loc (get-source &form &env))
         loc-map  (when (map?    loc-form) loc-form)
         loc-sym  (when (symbol? loc-form) loc-form)

         ns-form     (get opts :?ns-str (get loc-map :ns     (when loc-sym `(get ~loc-sym :ns))))
         file-form   (get opts :?file   (get loc-map :file   (when loc-sym `(get ~loc-sym :file))))
         line-form   (get opts :?line   (get loc-map :line   (when loc-sym `(get ~loc-sym :line))))
         column-form (get opts :?column (get loc-map :column (when loc-sym `(get ~loc-sym :column))))

         elide? (and (enc/const-forms? level ns-form) (timbre/-elide? level ns-form))]

     (when-not elide?
       (let [vargs-form
             (or vargs
                 (if (symbol? args)
                   `(enc/ensure-vec ~args)
                   `[              ~@args]))]

         ;; Note pre-resolved expansion
         `(taoensso.timbre/-log! ~config ~level ~ns-form ~file-form ~line-form ~column-form ~msg-type ~?err
                                 (delay ~vargs-form) ~?base-data ~callsite-id ~spying?
                                 ~(get opts :instant)
                                 ~(get opts :may-log?))))))

  ([level msg-type args & [opts]]
   (let [loc  (get-source &form &env)
         opts (assoc (conj {:loc loc} opts)
                     :level level, :msg-type msg-type, :args args)]
     `(timbre/log! ~opts))))

(defn make-ns [ns sci-ns ks]
  (reduce (fn [ns-map [var-name var]]
            (assoc ns-map var-name (sci/copy-var* var sci-ns)))
          {}
          (select-keys (ns-publics ns) ks)))

(defn println-appender
  "Returns a simple `println` appender for Clojure/Script.
  Use with ClojureScript requires that `cljs.core/*print-fn*` be set.
  :stream (clj only) - e/o #{:auto :*out* :*err* :std-err :std-out <io-stream>}."

  ;; Unfortunately no easy way to check if *print-fn* is set. Metadata on the
  ;; default throwing fn would be nice...

  [& [{:keys [stream] :or {stream :auto}}]]
  (let [stream
        (case stream
          :std-err timbre/default-err
          :std-out timbre/default-out
          stream)]
    {:enabled?   true
     :async?     false
     :min-level  nil
     :rate-limit nil
     :output-fn  :inherit
     :fn
     (fn [data]
       (let [{:keys [output_]} data
             stream
             (case stream
               :auto  (if (:error-level? data) @sci/err @sci/out)
               :*out* @sci/out
               :*err* @sci/err
               stream)]
         (binding [*out* stream]
           (enc/println-atomic (force output_)))))}))

(def default-config (assoc-in timbre/*config* [:appenders :println]
                              (println-appender {:stream :auto})))

(def config (sci/new-dynamic-var '*config* default-config
                                 {:ns tns}))

(defn swap-config! [f & args]
  (apply sci/alter-var-root config f args))

(defn set-config! [cfg]
  (swap-config! (fn [_old] cfg)))

(defn set-level! [level] (swap-config! assoc :min-level level))

(defn set-min-level! [level] (swap-config! (fn [cfg]
                                             (timbre/set-min-level cfg level))))

(defn merge-config! [m] (swap-config! (fn [old] (enc/nested-merge old m))))

(defn set-ns-min-level
  "Returns given Timbre `config` with its `:min-level` modified so that
  the given namespace has the specified minimum logging level.

  When no namespace is provided, `*ns*` will be used.
  When `?min-level` is nil, any minimum level specifications for the
  *exact* given namespace will be removed.

  See `*config*` docstring for more about `:min-level`.
  See also `set-min-level!` for a util to directly modify `*config*`."

  ([config    ?min-level] (set-ns-min-level config @sci/ns ?min-level)) ; No *ns* at Cljs runtime
  ([config ns ?min-level]
   (timbre/set-ns-min-level config ns ?min-level)))

(defmacro set-ns-min-level!
  "Like `set-ns-min-level` but directly modifies `*config*`.

     Can conveniently set the minimum log level for the current ns:
      (set-ns-min-level! :info) => Sets min-level for current *ns*

     See `set-ns-min-level` for details."

  ;; Macro to support compile-time Cljs *ns*
  ([   ?min-level] `(timbre/set-ns-min-level! ~(str @sci/ns) ~?min-level))
  ([ns ?min-level] `(timbre/swap-config! (fn [config#] (timbre/set-ns-min-level config# ~(str ns) ~?min-level)))))

(defmacro -log-and-rethrow-errors [?line & body]
  `(try (do ~@body)
        (catch Throwable e#
          (do
            #_(error e#) ; CLJ-865
            (timbre/log! :error :p [e#] ~{:?line ?line})
            (throw e#)))))


;; (defmacro log*  [config level & args] `(log! ~level  :p ~args ~{:loc (get-source &form &env), :config config}))
;; (defmacro log          [level & args] `(log! ~level  :p ~args ~{:loc (get-source &form &env)}))
(defmacro trace              [& args] `(timbre/log! :trace  :p ~args ~{:loc (get-source &form &env)}))
(defmacro debug              [& args] `(timbre/log! :debug  :p ~args ~{:loc (get-source &form &env)}))
(defmacro info               [& args] `(timbre/log! :info   :p ~args ~{:loc (get-source &form &env)}))
(defmacro warn               [& args] `(timbre/log! :warn   :p ~args ~{:loc (get-source &form &env)}))
(defmacro error              [& args] `(timbre/log! :error  :p ~args ~{:loc (get-source &form &env)}))
;; (defmacro fatal              [& args] `(timbre/log! :fatal  :p ~args ~{:loc (get-source &form &env)}))
;; (defmacro report             [& args] `(timbre/log! :report :p ~args ~{:loc (get-source &form &env)}))

     ;;; Log using format-style args
;; (defmacro logf* [config level & args] `(log! ~level  :f ~args ~{:loc (get-source &form &env), :config config}))
;; (defmacro logf         [level & args] `(log! ~level  :f ~args ~{:loc (get-source &form &env)}))
(defmacro tracef             [& args] `(timbre/log! :trace  :f ~args ~{:loc (get-source &form &env)}))
(defmacro debugf             [& args] `(timbre/log! :debug  :f ~args ~{:loc (get-source &form &env)}))
(defmacro infof              [& args] `(timbre/log! :info   :f ~args ~{:loc (get-source &form &env)}))
(defmacro warnf              [& args] `(timbre/log! :warn   :f ~args ~{:loc (get-source &form &env)}))
(defmacro errorf             [& args] `(timbre/log! :error  :f ~args ~{:loc (get-source &form &env)}))
;; (defmacro fatalf             [& args] `(timbre/log! :fatal  :f ~args ~{:loc (get-source &form &env)}))
;; (defmacro reportf            [& args] `(timbre/log! :report :f ~args ~{:loc (get-source &form &env)}))

(def timbre-namespace
  (assoc (make-ns 'taoensso.timbre tns [;; 'trace 'tracef 'debug 'debugf
                                        ;; 'info 'infof 'warn 'warnf
                                        ;; 'error 'errorf
                                        '-log! 'with-level
                                        'spit-appender '-spy 'spy
                                        'color-str
                                        'may-log?])
         'trace (sci/copy-var trace tns {:copy-meta-from taoensso.timbre/trace})
         'debug (sci/copy-var debug tns {:copy-meta-from taoensso.timbre/debug})
         'info  (sci/copy-var info tns {:copy-meta-from taoensso.timbre/info})
         'warn  (sci/copy-var warn tns {:copy-meta-from taoensso.timbre/warn})
         'error  (sci/copy-var error tns {:copy-meta-from taoensso.timbre/error})
         'tracef (sci/copy-var tracef tns {:copy-meta-from taoensso.timbre/tracef})
         'debugf (sci/copy-var debugf tns {:copy-meta-from taoensso.timbre/debugf})
         'infof  (sci/copy-var infof tns {:copy-meta-from taoensso.timbre/infof})
         'warnf  (sci/copy-var warnf tns {:copy-meta-from taoensso.timbre/warnf})
         'errorf  (sci/copy-var errorf tns {:copy-meta-from taoensso.timbre/errorf})
         'log! (sci/copy-var log! tns {:copy-meta-from taoensso.timbre/log!})
         '*config* config
         'set-config! (sci/copy-var set-config! tns {:copy-meta-from taoensso.timbre/set-config!})
         'swap-config! (sci/copy-var swap-config! tns {:copy-meta-from taoensso.timbre/swap-config!})
         'merge-config! (sci/copy-var merge-config! tns {:copy-meta-from taoensso.timbre/merge-config!})
         'set-level! (sci/copy-var set-level! tns {:copy-meta-from taoensso.timbre/set-level!})
         'set-min-level! (sci/copy-var set-min-level! tns {:copy-meta-from taoensso.timbre/set-min-level!})
         'println-appender (sci/copy-var println-appender tns {:copy-meta-from taoensso.timbre/println-appender})
         '-log-and-rethrow-errors (sci/copy-var -log-and-rethrow-errors tns)
         'set-min-level! (sci/copy-var set-min-level! tns {:copy-meta-from taoensso.timbre/set-min-level!})
         'set-ns-min-level (sci/copy-var set-ns-min-level tns {:copy-meta-from taoensso.timbre/set-ns-min-level})
         'set-ns-min-level! (sci/copy-var set-ns-min-level! tns {:copy-meta-from taoensso.timbre/set-ns-min-level!})))

(def enc-ns (sci/create-ns 'taoensso.encore))

(def encore-namespace
  {'catching (sci/copy-var enc/catching enc-ns)
   'try* (sci/copy-var enc/try* enc-ns)
   'ensure-vec (sci/copy-var enc/ensure-vec enc-ns)})

(def truss-ns (sci/create-ns 'taoensso.truss))

(def truss-namespace
  {'try* (sci/copy-var truss/try* truss-ns)})

(def timbre-appenders-namespace
  (let [tan (sci/create-ns 'taoensso.timbre.appenders.core nil)]
    {'println-appender (sci/copy-var println-appender tan {:copy-meta-from taoensso.timbre/println-appender})
     'spit-appender (sci/copy-var #_:clj-kondo/ignore timbre/spit-appender tan)}))

;;;; clojure.tools.logging

(defn- force-var "To support dynamic vars, etc."
  [x] (if (instance? clojure.lang.IDeref x) (deref x) x))

(deftype Logger [logger-ns-str timbre-config]
  clojure.tools.logging.impl/Logger

  (enabled? [_ level]
    ;; No support for per-call config
    (timbre/may-log? level logger-ns-str
                     (force-var timbre-config)))

  (write! [_ level throwable message]
    (log! level :p
          [message] ; No support for pre-msg raw args
          {:config  (force-var timbre-config) ; No support for per-call config
           :?ns-str logger-ns-str
           :?file   nil ; No support
           :?line   nil ; ''
           :?err    throwable})))

(deftype LoggerFactory [get-logger-fn]
  clojure.tools.logging.impl/LoggerFactory
  (name [_] "Timbre")
  (get-logger [_ logger-ns] (get-logger-fn logger-ns)))

(alter-var-root
 #'clojure.tools.logging/*logger-factory*
 (fn [_]
   (LoggerFactory.
    (enc/memoize (fn [logger-ns] (Logger. (str logger-ns) config))))))

(def lns (sci/create-ns 'clojure.tools.logging nil))

(defmacro log
  "Evaluates and logs a message only if the specified level is enabled. See log*
  for more details."
  ([level message]
   `(clojure.tools.logging/log ~level nil ~message))
  ([level throwable message]
   `(clojure.tools.logging/log ~(deref sci/ns) ~level ~throwable ~message))
  ([logger-ns level throwable message]
   `(clojure.tools.logging/log clojure.tools.logging/*logger-factory* ~logger-ns ~level ~throwable ~message))
  ([logger-factory logger-ns level throwable message]
   `(let [logger# (impl/get-logger ~logger-factory ~logger-ns)]
      (if (impl/enabled? logger# ~level)
        (clojure.tools.logging/log* logger# ~level ~throwable ~message)))))

(def tools-logging-namespace
  (assoc (make-ns 'clojure.tools.logging lns ['debug 'debugf 'info 'infof 'warn 'warnf 'error 'errorf
                                              'logp 'logf '*logger-factory* 'log*
                                              'trace 'tracef])
         'log (sci/copy-var log lns {:copy-meta-from clojure.tools.logging/log})))

(def lins (sci/create-ns 'clojure.tools.logging.impl nil))

(def tools-logging-impl-namespace
  (make-ns 'clojure.tools.logging.impl lins ['get-logger 'enabled?]))

(def tlr-ns (sci/create-ns 'clojure.tools.logging.readable nil))

(def tools-logging-readable-namespace
  (make-ns 'clojure.tools.logging.readable tlr-ns ['trace 'tracef 'debug 'debugf 'info 'infof
                                                   'warn 'warnf 'error 'errorf 'fatal 'fatalf
                                                   'logf 'logp 'spyf]))
