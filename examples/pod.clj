(ns pod
  (:refer-clojure :exclude [read read-string])
  (:require [babashka.pods :as pods]
            [bencode.core :as bencode]
            [clojure.core.async :as async]
            [clojure.edn :as edn])
  (:import [java.io PushbackInputStream])
  (:gen-class))

(def stdin (PushbackInputStream. System/in))

(defn write [v]
  (bencode/write-bencode System/out v)
  (.flush System/out))

(defn read-string [^"[B" v]
  (String. v))

(defn read []
  (bencode/read-bencode stdin))

(defn run-pod [& _args]
  (loop []
    (let [message (try (read)
                       (catch java.io.EOFException _
                         ::EOF))]
      (when-not (identical? ::EOF message)
        (let [op (get message "op")
              op (read-string op)
              op (keyword op)]
          (case op
            :describe (do (write {"format" "edn"
                                  "vars" [{"namespace" "pod.test-pod"
                                           "name" "add-sync"}
                                          {"namespace" "pod.test-pod"
                                           "name" "range-stream"
                                           "async" "true"}]})
                          (recur))
            :invoke (let [var (-> (get message "var")
                                  read-string
                                  symbol)
                          id (-> (get message "id")
                                 read-string)
                          args (get message "args")
                          args (read-string args)
                          args (edn/read-string args)]
                      (case var
                        pod.test-pod/add-sync (write
                                               {"value" (pr-str (apply + args))
                                                "id" id
                                                "status" ["done"]})
                        pod.test-pod/range-stream
                        (let [rng (apply range args)]
                          (doseq [v rng]
                            (write
                             {"value" (pr-str v)
                              "id" id})
                            (Thread/sleep 100))
                          (write
                           {"status" ["done"]
                            "id" id})))
                      (recur))))))))

(let [f (first *command-line-args*)]
  (case f
    "--run-as-pod" (run-pod)
    ;; else
    (do (pods/load-pod ["bb" "examples/pod.clj" "--run-as-pod"])
        (require '[pod.test-pod])
        (prn ((resolve 'pod.test-pod/add-sync) 1 2 3))
        (let [chan ((resolve 'pod.test-pod/range-stream) 1 10)]
          (loop []
            (when-let [x (async/<!! chan)]
              (prn x)
              (recur)))))))
