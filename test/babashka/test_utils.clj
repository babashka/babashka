(ns babashka.test-utils
  (:require
   [babashka.main :as main]
   [me.raynes.conch :refer [let-programs] :as sh]))

(set! *warn-on-reflection* true)

(defn bb-jvm [input & args]
  (let [sw (java.io.StringWriter.)
        res (binding [*err* sw]
              (with-out-str
                (if input
                  (with-in-str input
                    (apply main/main args))
                  (apply main/main input args))))]
    (if-let [err ^String (not-empty (str sw))]
      (throw (Exception. err)) res)))

(defn bb-native [input & args]
  (let-programs [bb "./bb"]
    (try (if input
           (apply bb (conj (vec args)
                           {:in input}))
           (apply bb input args))
         (catch Exception e
           (let [d (ex-data e)
                 err-msg (or (:stderr (ex-data e)) "")]
             (throw (ex-info err-msg d)))))))

(def bb
  (case (System/getenv "BABASHKA_TEST_ENV")
    "jvm" #'bb-jvm
    "native" #'bb-native
    #'bb-jvm))

(if (= bb #'bb-jvm)
  (println "==== Testing JVM version")
  (println "==== Testing native version"))
