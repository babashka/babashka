(ns babashka.test-utils
  (:require
   [babashka.main :as main]
   [me.raynes.conch :refer [let-programs] :as sh]
   [sci.core :as sci]
   [sci.impl.vars :as vars]))

(set! *warn-on-reflection* true)

(defn bb-jvm [input & args]
  (reset! main/cp-state nil)
  (let [os (java.io.StringWriter.)
        es (java.io.StringWriter.)
        is (when input
             (java.io.StringReader. input))
        bindings-map (cond-> {sci/out os
                              sci/err es}
                       is (assoc sci/in is))]
    (try
      (when input (vars/bindRoot sci/in is))
      (vars/bindRoot sci/out os)
      (vars/bindRoot sci/err es)
      (sci/with-bindings bindings-map
          (let [res (binding [*out* os
                              *err* es]
                      (if input
                        (with-in-str input (apply main/main args))
                        (apply main/main args)))]
            (if (zero? res)
              (str os)
              (throw (ex-info (str es)
                              {:stdout (str os)
                               :stderr (str es)})))))
      (finally
        (when input (vars/bindRoot sci/in *in*))
        (vars/bindRoot sci/out *out*)
        (vars/bindRoot sci/err *err*)))))

(defn bb-native [input & args]
  (let-programs [bb "./bb"]
    (try (if input
           (apply bb (conj (vec args)
                           {:in input}))
           (apply bb args))
         (catch Exception e
           (let [d (ex-data e)
                 err-msg (or (:stderr (ex-data e)) "")]
             (throw (ex-info err-msg d)))))))

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
