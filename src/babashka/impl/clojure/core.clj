(ns babashka.impl.clojure.core
  {:no-doc true}
  (:refer-clojure :exclude [future read+string clojure-version with-precision
                            send-via send send-off sync into-array load])
  (:require [babashka.impl.common :as common]
            [clojure.core :as c]
            [clojure.string :as str]
            [sci.core :as sci]
            [sci.ctx-store :as store]
            [sci.impl.copy-vars :refer [copy-core-var new-var]]
            [sci.impl.parser :as parser]
            [sci.impl.utils :refer [clojure-core-ns]]
            [sci.impl.vars :as vars]))

(defn core-dynamic-var
  ([sym] (core-dynamic-var sym nil))
  ([sym init-val] (sci/new-dynamic-var sym init-val {:ns clojure-core-ns})))

(def data-readers parser/data-readers)
(def command-line-args (core-dynamic-var '*command-line-args*))
(def warn-on-reflection (core-dynamic-var '*warn-on-reflection* false))
(def compile-files (core-dynamic-var '*compile-files* false))
(def unchecked-math (core-dynamic-var '*unchecked-math* false))
(def math-context (core-dynamic-var '*math-context*))
(def compile-path (core-dynamic-var '*compile-path* *compile-path*))
(def compiler-options (core-dynamic-var '*compiler-options*))
(def repl (core-dynamic-var '*repl* true)) ;; set to true, basically just a dummy for now

(defn read+string
  "Added for compatibility. Must be used with
  clojure.lang.LineNumberingPushbackReader. Does not support all of
  the options from the original yet."
  ([sci-ctx]
   (read+string sci-ctx @sci/in))
  ([sci-ctx stream]
   (read+string sci-ctx stream true nil))
  ([sci-ctx stream eof-error? eof-value]
   (read+string sci-ctx stream eof-error? eof-value false))
  ([sci-ctx ^clojure.lang.LineNumberingPushbackReader stream _eof-error? eof-value _recursive?]
   (let [_ (.captureString stream)
         v (sci/parse-next sci-ctx stream {:eof eof-value})
         s (str/trim (.getString stream))]
     [(if (identical? :sci.core/eof v)
        eof-value
        v) s])))

(defmacro with-precision
  "Sets the precision and rounding mode to be used for BigDecimal operations.

  Usage: (with-precision 10 (/ 1M 3))
  or:    (with-precision 10 :rounding HALF_DOWN (/ 1M 3))

  The rounding mode is one of CEILING, FLOOR, HALF_UP, HALF_DOWN,
  HALF_EVEN, UP, DOWN and UNNECESSARY; it defaults to HALF_UP."
  [precision & exprs]
  (let [[body rm] (if (= :rounding (first exprs))
                    [(next (next exprs))
                     `((. java.math.RoundingMode ~(second exprs)))]
                    [exprs nil])]
    `(clojure.core/-with-precision (java.math.MathContext. ~precision ~@rm)
                                   (fn [] ~@body))))

(defn -with-precision [math-context body-fn]
  (binding [*math-context* math-context]
    (body-fn)))


;;;; Agents

(defn send-via
  "Dispatch an action to an agent. Returns the agent immediately.
  Subsequently, in a thread supplied by executor, the state of the agent
  will be set to the value of:
  (apply action-fn state-of-agent args)"
  [executor ^clojure.lang.Agent a f & args]
  (apply c/send-via executor a (vars/binding-conveyor-fn f) args))

(defn send
  "Dispatch an action to an agent. Returns the agent immediately.
  Subsequently, in a thread from a thread pool, the state of the agent
  will be set to the value of:
  (apply action-fn state-of-agent args)"
  [^clojure.lang.Agent a f & args]
  (apply send-via clojure.lang.Agent/pooledExecutor a f args))

(defn send-off
  "Dispatch a potentially blocking action to an agent. Returns the
  agent immediately. Subsequently, in a separate thread, the state of
  the agent will be set to the value of:
  (apply action-fn state-of-agent args)"
  [^clojure.lang.Agent a f & args]
  (apply send-via clojure.lang.Agent/soloExecutor a f args))

;;;; End agents

;;;; STM

(defn -run-in-transaction [f]
  (clojure.lang.LockingTransaction/runInTransaction f))

(defmacro sync
  "transaction-flags => TBD, pass nil for now
  Runs the exprs (in an implicit do) in a transaction that encompasses
  exprs and any nested calls.  Starts a transaction if none is already
  running on this thread. Any uncaught exception will abort the
  transaction and flow out of sync. The exprs may be run more than
  once, but any effects on Refs will be atomic."
  {:added "1.0"}
  [_flags-ignored-for-now & body]
  `(clojure.core/-run-in-transaction (fn [] ~@body)))

(defn into-array
  "Returns an array with components set to the values in aseq. The array's
  component type is type if provided, or the type of the first value in
  aseq if present, or Object. All values in aseq must be compatible with
  the component type. Class objects for the primitive types can be obtained
  using, e.g., Integer/TYPE."
  {:added "1.0"
   :static true}
  ([aseq]
   (try (clojure.lang.RT/seqToTypedArray (seq aseq))
        (catch Throwable _
          (clojure.lang.RT/seqToTypedArray Object (seq aseq)))))
  ([type aseq]
   (clojure.lang.RT/seqToTypedArray type (seq aseq))))

(defn- root-resource
  "Returns the root directory path for a lib"
  {:tag String}
  [lib]
  (str \/
       (.. (name lib)
           (replace \- \_)
           (replace \. \/))))

(defn- root-directory
  "Returns the root resource path for a lib"
  [lib]
  (let [d (root-resource lib)]
    (subs d 0 (.lastIndexOf d "/"))))

(defn load
  "Loads Clojure code from resources in classpath. A path is interpreted as
  classpath-relative if it begins with a slash or relative to the root
  directory for the current namespace otherwise."
  {:redef true
   :added "1.0"}
  [& paths]
  (doseq [^String path paths]
    (let [^String path (if (.startsWith path "/")
                         path
                         (str (root-directory (sci/ns-name @sci/ns)) \/ path))
          ns (-> path
                 (subs 1)
                 (str/replace "_" "-")
                 (str/replace "/" ".")
                 symbol)]
      (sci/binding [sci/ns sci/ns]
        (sci/eval-form (store/get-ctx) (list 'require (list 'quote ns)))))))

(def core-extras
  {;; agents
   'agent (copy-core-var agent)
   'agent-error (copy-core-var agent-error)
   'await (copy-core-var await)
   'await-for (copy-core-var await-for)
   'error-handler (copy-core-var error-handler)
   'error-mode (copy-core-var error-mode)
   'get-validator (copy-core-var get-validator)
   'send (copy-core-var send)
   'send-off (copy-core-var send-off)
   'send-via (copy-core-var send-via)
   'release-pending-sends (copy-core-var release-pending-sends)
   'restart-agent (copy-core-var restart-agent)
   'set-validator! (copy-core-var set-validator!)
   'set-error-handler! (copy-core-var set-error-handler!)
   'set-error-mode! (copy-core-var set-error-mode!)
   ;; end agents
   'file-seq (copy-core-var file-seq)
   'promise (copy-core-var promise)
   'deliver (copy-core-var deliver)
   'shutdown-agents (copy-core-var shutdown-agents)
   'slurp (copy-core-var slurp)
   'spit (copy-core-var spit)
   'Throwable->map (copy-core-var Throwable->map)
   'tap> (copy-core-var tap>)
   'add-tap (copy-core-var add-tap)
   'remove-tap (copy-core-var remove-tap)
   '*data-readers* data-readers
   'default-data-readers (copy-core-var default-data-readers)
   'xml-seq (copy-core-var xml-seq)
   'read+string (new-var 'read+string (fn [& args]
                                        (apply read+string (common/ctx) args)))
   '*command-line-args* command-line-args
   '*warn-on-reflection* warn-on-reflection
   '*compile-files* compile-files
   '*unchecked-math* unchecked-math
   '*math-context* math-context
   '*compiler-options* compiler-options
   '*compile-path* compile-path
   '*source-path* sci/file
   'with-precision (sci/copy-var with-precision clojure-core-ns)
   '-with-precision (sci/copy-var -with-precision clojure-core-ns)
   ;; STM
   'alter (sci/copy-var alter clojure-core-ns)
   'commute (sci/copy-var commute clojure-core-ns)
   'dosync (sci/copy-var dosync clojure-core-ns)
   '-run-in-transaction (sci/copy-var -run-in-transaction clojure-core-ns)
   'sync (sci/copy-var sync clojure-core-ns)
   'ref (sci/copy-var ref clojure-core-ns)
   'ref-set (sci/copy-var ref-set clojure-core-ns)
   'ensure (sci/copy-var ensure clojure-core-ns)
   ;; end STM
   'update-vals (sci/copy-var update-vals clojure-core-ns)
   'update-keys (sci/copy-var update-keys clojure-core-ns)
   'parse-boolean (sci/copy-var parse-boolean clojure-core-ns)
   'parse-double (sci/copy-var parse-double clojure-core-ns)
   'parse-long (sci/copy-var parse-long clojure-core-ns)
   'parse-uuid (sci/copy-var parse-uuid clojure-core-ns)
   'random-uuid (sci/copy-var random-uuid clojure-core-ns)
   'NaN? (sci/copy-var NaN? clojure-core-ns)
   'infinite? (sci/copy-var infinite? clojure-core-ns)
   'iteration (sci/copy-var iteration clojure-core-ns)
   'abs (sci/copy-var abs clojure-core-ns)
   'StackTraceElement->vec (sci/copy-var StackTraceElement->vec clojure-core-ns)
   'into-array (sci/copy-var into-array clojure-core-ns)
   'print-method (sci/copy-var print-method clojure-core-ns)
   'print-dup (sci/copy-var print-dup clojure-core-ns)
   'PrintWriter-on (sci/copy-var PrintWriter-on clojure-core-ns)
   'set-agent-send-executor! (sci/copy-var set-agent-send-executor! clojure-core-ns)
   'set-agent-send-off-executor! (sci/copy-var set-agent-send-off-executor! clojure-core-ns)
   ;; 1.12
   'splitv-at (sci/copy-var splitv-at clojure-core-ns)
   'stream-transduce! (sci/copy-var stream-transduce! clojure-core-ns)
   'partitionv (sci/copy-var partitionv clojure-core-ns)
   'stream-into! (sci/copy-var stream-into! clojure-core-ns)
   'stream-reduce! (sci/copy-var stream-reduce! clojure-core-ns)
   'stream-seq! (sci/copy-var stream-seq! clojure-core-ns)
   'partitionv-all (sci/copy-var partitionv-all clojure-core-ns)
   '*repl* repl
   'load (sci/copy-var load clojure-core-ns)
   }
  )
