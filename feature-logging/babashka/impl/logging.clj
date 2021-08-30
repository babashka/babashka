(ns babashka.impl.logging
  (:require [clojure.tools.logging]
            [clojure.tools.logging.impl :as impl]
            [clojure.tools.logging.readable]
            [sci.core :as sci]
            [taoensso.encore :as enc :refer [have]]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.core :as appenders]))

;;;; timbre

(def tns (sci/create-ns 'taoensso.timbre nil))

(defn- fline [and-form] (:line (meta and-form)))

(defmacro log! ; Public wrapper around `-log!`
  "Core low-level log macro. Useful for tooling, etc.
    * `level`    - must eval to a valid logging level
    * `msg-type` - must eval to e/o #{:p :f nil}
    * `opts`     - ks e/o #{:config :?err :?ns-str :?file :?line :?base-data :spying?}
  Supports compile-time elision when compile-time const vals
  provided for `level` and/or `?ns-str`."
  [level msg-type args & [opts]]
  (have [:or nil? sequential?] args) ; To allow -> (delay [~@args])
  (let [{:keys [?ns-str] :or {?ns-str (str @sci/ns)}} opts]
    ;; level, ns may/not be compile-time consts:
    (when-not (timbre/-elide? level ?ns-str)
      (let [{:keys [config ?err ?file ?line ?base-data spying?]
             :or   {config 'taoensso.timbre/*config*
                    ?err   :auto ; => Extract as err-type v0
                    ?file  @sci/file
                    ;; NB waiting on CLJ-865:
                    ?line (fline &form)}} opts

            ?file (when (not= ?file "NO_SOURCE_PATH") ?file)

            ;; Identifies this particular macro expansion; note that this'll
            ;; be fixed for any fns wrapping `log!` (notably `tools.logging`,
            ;; `slf4j-timbre`, etc.):
            callsite-id
            (hash [level msg-type args ; Unevaluated args (arg forms)
                   ?ns-str ?file ?line (rand)])]

        `(taoensso.timbre/-log! ~config ~level ~?ns-str ~?file ~?line ~msg-type ~?err
                                (delay [~@args]) ~?base-data ~callsite-id ~spying?)))))

(defn make-ns [ns sci-ns ks]
  (reduce (fn [ns-map [var-name var]]
            (let [m (meta var)
                  no-doc (:no-doc m)
                  doc (:doc m)
                  arglists (:arglists m)]
              (if no-doc ns-map
                  (assoc ns-map var-name
                         (sci/new-var (symbol var-name) @var
                                      (cond-> {:ns sci-ns
                                               :name (:name m)}
                                        (:macro m) (assoc :macro true)
                                        doc (assoc :doc doc)
                                        arglists (assoc :arglists arglists)))))))
          {}
          (select-keys (ns-publics ns) ks)))

(def atomic-println @#'appenders/atomic-println)

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
           (atomic-println (force output_)))))}))

(def default-config (assoc-in timbre/*config* [:appenders :println]
                              (println-appender {:stream :auto})))

(def config (sci/new-dynamic-var '*config* default-config
                                 {:ns tns}))

(defn swap-config! [f & args]
  (apply sci/alter-var-root config f args))

(defn set-level! [level] (swap-config! (fn [m] (assoc m :min-level level))))

(defn merge-config! [m] (swap-config! (fn [old] (enc/nested-merge old m))))

(def timbre-namespace
  (assoc (make-ns 'taoensso.timbre tns ['trace 'tracef 'debug 'debugf
                                        'info 'infof 'warn 'warnf
                                        'error 'errorf
                                        '-log! 'with-level
                                        'spit-appender])
         'log! (sci/copy-var log! tns)
         '*config* config
         'swap-config! (sci/copy-var swap-config! tns)
         'merge-config! (sci/copy-var merge-config! tns)
         'set-level! (sci/copy-var set-level! tns)
         'println-appender (sci/copy-var println-appender tns)))

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
         'log (sci/copy-var log lns)))

(def lins (sci/create-ns 'clojure.tools.logging.impl nil))

(def tools-logging-impl-namespace
  (make-ns 'clojure.tools.logging.impl lins ['get-logger 'enabled?]))

(def tlr-ns (sci/create-ns 'clojure.tools.logging.readable nil))

(def tools-logging-readable-namespace
  (make-ns 'clojure.tools.logging.readable tlr-ns ['trace 'tracef 'debug 'debugf 'info 'infof
                                                          'warn 'warnf 'error 'errorf 'fatal 'fatalf
                                                          'logf 'logp 'spyf]))
