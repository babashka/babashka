#!/usr/bin/env bb

(require '[babashka.curl :as curl]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str])

(def url "http://speedtest-ams2.digitalocean.com/")

(def get-endpoints
  (let [{:keys [body]} (curl/get url)]
    (re-seq #"speedtest\-.+.digitalocean.com" body)))

(defn get-average [result]
  (-> result
      str/split-lines
      last
      (str/split #"/")
      (get 4)))

(def mac? (str/starts-with? (System/getProperty "os.name") "Mac"));; TODO: test on Windows

(def timeout-arg (if mac? "-t3" "-w3"))

(defn ping-result [endpoint]
  (let [{:keys [out]} (shell/sh "ping" "-c5" timeout-arg endpoint)
        msg (str endpoint " => " (get-average out) "ms")]
    (println msg)))

(doseq [endpoint get-endpoints]
  (ping-result endpoint))
