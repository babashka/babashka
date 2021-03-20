(ns babashka.test-utils
  (:require
   [babashka.impl.classpath :as cp]
   [babashka.main :as main]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [sci.core :as sci]
   [sci.impl.vars :as vars]))

(set! *warn-on-reflection* true)

(def ^:dynamic *bb-edn-path* nil)

(defn bb-jvm [input-or-opts & args]
  (reset! cp/cp-state nil)
  (reset! main/env {})
  (when-let [path *bb-edn-path*]
    (reset! main/bb-edn (edn/read-string (slurp path))))
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
            ;; (prn :err (str es))
            (if (zero? res)
              (str os)
              (throw (ex-info (str es)
                              {:stdout (str os)
                               :stderr (str es)})))))
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
                          :env (assoc (into {} (System/getenv))
                                      "BABASHKA_EDN" *bb-edn-path*))))
        res (deref res)
        exit (:exit res)
        error? (pos? exit)]
    (if error? (throw (ex-info (or (:err res) "") {}))
        (:out res))))

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
