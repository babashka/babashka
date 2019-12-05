(ns babashka.test-utils
  (:require
   [babashka.main :as main]
   [me.raynes.conch :refer [let-programs] :as sh]
   [sci.core :as sci]))

(set! *warn-on-reflection* true)

(defn bb-jvm [input & args]
  (let [os (java.io.StringWriter.)
        es (java.io.StringWriter.)
        is (when input
             (java.io.StringReader. input))
        bindings-map (cond-> {sci/out os
                              sci/err es}
                       is (assoc sci/in is))]
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
                           :stderr (str es)})))))))

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
