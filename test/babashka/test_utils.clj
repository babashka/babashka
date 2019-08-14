(ns babashka.test-utils
  (:require
   [babashka.main :as main]
   [me.raynes.conch :refer [let-programs] :as sh]))

(set! *warn-on-reflection* true)

(defn bb-jvm [input & args]
  (with-out-str
    (if input
      (with-in-str input
        (apply main/main args))
      (apply main/main input args))))

(defn bb-native [input & args]
  (let-programs [bb "./bb"]
    (binding [sh/*throw* false]
      (if input
        (apply bb (conj (vec args)
                        {:in input}))
        (apply bb input args)))))

(def bb
  (case (System/getenv "BABASHKA_TEST_ENV")
    "jvm" #'bb-jvm
    "native" #'bb-native
    #'bb-jvm))

(if (= bb #'bb-jvm)
  (println "==== Testing JVM version")
  (println "==== Testing native version"))
