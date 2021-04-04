(ns babashka.impl.clojure.core
  {:no-doc true}
  (:refer-clojure :exclude [future read+string clojure-version])
  (:require [babashka.impl.common :as common]
            [borkdude.graal.locking :as locking]
            [clojure.string :as str]
            [sci.core :as sci]
            [sci.impl.namespaces :refer [copy-core-var #_clojure-core-ns]]))

(defn locking* [form bindings v f & args]
  (apply @#'locking/locking form bindings v f args))

(defn time*
  "Evaluates expr and prints the time it took.  Returns the value of
  expr."
  [_ _ expr]
  `(let [start# (. System (nanoTime))
         ret# ~expr]
     (prn (str "Elapsed time: " (/ (double (- (. System (nanoTime)) start#)) 1000000.0) " msecs"))
     ret#))

(def data-readers (sci/new-dynamic-var '*data-readers* nil))
(def command-line-args (sci/new-dynamic-var '*command-line-args* nil))
(def warn-on-reflection (sci/new-dynamic-var '*warn-on-reflection* false))

;; (def major (:major *clojure-version*))
;; (def minor (:minor *clojure-version*))
;; (def incremental (:incremental *clojure-version*))
;; (def qualifier (str "sci" (when-let [q (:qualifier *clojure-version*)]
;;                             (str "+" q))))

;; (def clojure-ver {:major       major
;;                   :minor       minor
;;                   :incremental incremental
;;                   :qualifier   qualifier})

;; (defn clojure-version
;;   "Returns clojure version as a printable string."
;;   {:added "1.0"}
;;   []
;;   (str major
;;        "."
;;        minor
;;        (when-let [i incremental]
;;          (str "." i))
;;        (when-let [q qualifier]
;;          (when (pos? (count q)) (str "-" q)))
;;        (when incremental
;;          "-SNAPSHOT")))

;; (def clojure-version-var (sci/new-dynamic-var '*clojure-version* clojure-ver))

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

(def core-extras
  {'file-seq (copy-core-var file-seq)
   'agent (copy-core-var agent)
   'send (copy-core-var send)
   'send-off (copy-core-var send-off)
   'promise (copy-core-var promise)
   'deliver (copy-core-var deliver)
   'locking (with-meta locking* {:sci/macro true})
   'shutdown-agents (copy-core-var shutdown-agents)
   'slurp (copy-core-var slurp)
   'spit (copy-core-var spit)
   'time (with-meta time* {:sci/macro true})
   'Throwable->map (copy-core-var Throwable->map)
   'tap> (copy-core-var tap>)
   'add-tap (copy-core-var add-tap)
   'remove-tap (copy-core-var remove-tap)
   '*data-readers* data-readers
   'default-data-readers default-data-readers
   'xml-seq (copy-core-var xml-seq)
   'read+string (fn [& args]
                  (apply read+string @common/ctx args))
   '*command-line-args* command-line-args
   '*warn-on-reflection* warn-on-reflection
   ;;'*clojure-version* clojure-version-var
   ;;'clojure-version (sci/copy-var clojure-version clojure-core-ns)
   }
  )
