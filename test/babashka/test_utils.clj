(ns babashka.test-utils
  (:require
   [babashka.fs :as fs]
   [babashka.impl.classpath :as cp]
   [babashka.impl.common :as common]
   [babashka.main :as main]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :as test :refer [*report-counters*]]
   [sci.core :as sci]
   [sci.impl.vars :as vars]))

(set! *warn-on-reflection* true)

(defn string-replace-if-windows [match replacement]
  (fn [s]
    (if main/windows?
      (str/replace s match replacement)
      s)))

(def normalize (string-replace-if-windows "\r\n" "\n"))

(def escape-file-paths (string-replace-if-windows "\\" "\\\\"))

(def ^:dynamic *bb-edn-path* nil)

(defmethod clojure.test/report :begin-test-var [m]
  (println "===" (-> m :var meta :name))
  (println))

(defmethod clojure.test/report :end-test-var [_m]
  (let [{:keys [:fail :error]} @*report-counters*]
    (when (and (= "true" (System/getenv "BABASHKA_FAIL_FAST"))
               (or (pos? fail) (pos? error)))
      (println "=== Failing fast")
      (System/exit 1))))

(defn bb-jvm [input-or-opts & args]
  (reset! cp/cp-state nil)
  (reset! main/env {})
  (if-let [path *bb-edn-path*]
    (let [raw (slurp path)]
      (vreset! common/bb-edn
               (assoc (edn/read-string raw)
                      :raw raw)))
    (vreset! common/bb-edn nil))
  (let [os (java.io.StringWriter.)
        es (if-let [err (:err input-or-opts)]
             err (java.io.StringWriter.))
        in (if (string? input-or-opts)
             input-or-opts (:in input-or-opts))
        is (when in
             (java.io.StringReader. in))
        bindings-map (cond-> {sci/out os
                              sci/err es}
                       is (assoc sci/in is))]
    (try
      (when (string? input-or-opts) (vars/bindRoot sci/in is))
      (vars/bindRoot sci/out os)
      (vars/bindRoot sci/err es)
      (sci/with-bindings bindings-map
          (let [res (binding [*out* os
                              *err* es]
                      (if (string? input-or-opts)
                        (with-in-str input-or-opts (apply main/main args))
                        (apply main/main args)))]
            (if (zero? res)
              (normalize (str os))
              (do
                (println (str os))
                (throw (ex-info (str es)
                                  {:stdout (str os)
                                   :stderr (str es)}))))))
      (finally
        (when (string? input-or-opts) (vars/bindRoot sci/in *in*))
        (vars/bindRoot sci/out *out*)
        (vars/bindRoot sci/err *err*)))))

(defn bb-native [input & args]
  (let [res (p/process (into ["./bb"] args)
                       (cond-> {:in input
                                :out :string
                                :err :string}
                         *bb-edn-path*
                         (assoc
                          :extra-env (assoc (into {} (System/getenv))
                                            "BABASHKA_EDN" *bb-edn-path*))))
        res (deref res)
        exit (:exit res)
        error? (pos? exit)]
    (if error? (throw (ex-info (or (:err res) "") {}))
               (normalize (:out res)))))

(def bb
  (case (System/getenv "BABASHKA_TEST_ENV")
    "jvm" #'bb-jvm
    "native" #'bb-native
    #'bb-jvm))

(def jvm? (= bb #'bb-jvm))
(def native? (not jvm?))

(if jvm?
  (println "==== Testing JVM version")
  (println "==== Testing native version"))

(defn socket-loop [^java.net.ServerSocket server]
  (with-open [listener server]
    (loop []
      (with-open [socket (.accept listener)]
        (let [input-stream (.getInputStream socket)]
          (print (slurp input-stream))
          (flush)))
      (recur))))

(defn start-server! [port]
  (let [server (java.net.ServerSocket. port)]
    (future (socket-loop server))
    server))

(defn stop-server! [^java.net.ServerSocket server]
  (.close server))

(defmacro with-config [cfg & body]
  `(let [temp-dir# (fs/create-temp-dir)
         bb-edn-file# (fs/file temp-dir# "bb.edn")]
     (binding [*print-meta* true]
       (spit bb-edn-file# ~cfg))
     (binding [*bb-edn-path* (str bb-edn-file#)]
       ~@body)))
