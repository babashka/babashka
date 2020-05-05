(ns pod
  (:refer-clojure :exclude [read read-string])
  (:require [babashka.pods :as pods]
            [bencode.core :as bencode]
            [cheshire.core :as cheshire]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io PushbackInputStream])
  (:gen-class))

(def debug? false)

(defn debug [& args]
  (when debug?
    (binding [*out* (io/writer "/tmp/log.txt" :append true)]
      (apply println args))))

(def stdin (PushbackInputStream. System/in))

(defn write [v]
  (bencode/write-bencode System/out v)
  (.flush System/out))

(defn read-string [^"[B" v]
  (String. v))

(defn read []
  (bencode/read-bencode stdin))

(defn run-pod [cli-args]
  (let [format (if (contains? cli-args "--json")
                 :json
                 :edn)
        write-fn (if (identical? :json format)
                   cheshire/generate-string
                   pr-str)
        read-fn (if (identical? :json format)
                  #(cheshire/parse-string % true)
                  edn/read-string)]
    (loop []
      (let [message (try (read)
                         (catch java.io.EOFException _
                           ::EOF))]
        (when-not (identical? ::EOF message)
          (let [op (get message "op")
                op (read-string op)
                op (keyword op)]
            (case op
              ;; TODO:
              ;; group by namespace
              :describe (do (write {"format" (if (= format :json)
                                               "json"
                                               "edn")
                                    "vars" [{"namespace" "pod.test-pod"
                                             "name" "add-sync"}
                                            {"namespace" "pod.test-pod"
                                             "name" "range-stream"
                                             "async" "true"}
                                            {"namespace" "pod.test-pod"
                                             "name" "assoc"}
                                            {"namespace" "pod.test-pod"
                                             "name" "error"}
                                            {"namespace" "pod.test-pod"
                                             "name" "print"}
                                            {"namespace" "pod.test-pod"
                                             "name" "print-err"}]})
                            (recur))
              :invoke (let [var (-> (get message "var")
                                    read-string
                                    symbol)
                            _ (debug "var" var)
                            id (-> (get message "id")
                                   read-string)
                            args (get message "args")
                            args (read-string args)
                            args (read-fn args)]
                        (case var
                          pod.test-pod/add-sync (write
                                                 {"value" (write-fn (apply + args))
                                                  "id" id
                                                  "status" ["done"]})
                          pod.test-pod/range-stream
                          (let [rng (apply range args)]
                            (doseq [v rng]
                              (write
                               {"value" (write-fn v)
                                "id" id})
                              (Thread/sleep 100))
                            (write
                             {"status" ["done"]
                              "id" id}))
                          pod.test-pod/assoc
                          (write
                           {"value" (write-fn (apply assoc args))
                            "status" ["done"]
                            "id" id})
                          pod.test-pod/error
                          (write
                           {"ex-data" (write-fn {:args args})
                            "ex-message" (str "Illegal arguments")
                            "status" ["done" "error"]
                            "id" id})
                          pod.test-pod/print
                          (do (write
                               {"out" (pr-str args)
                                "id" id})
                              (write
                               {"status" ["done"]
                                "id" id}))
                          pod.test-pod/print-err
                          (do (write
                               {"err" (pr-str args)
                                "id" id})
                              (write
                               {"status" ["done"]
                                "id" id})))
                        (recur)))))))))

(let [cli-args (set *command-line-args*)]
  (if (contains? cli-args "--run-as-pod")
    (do (debug "running pod with cli args" cli-args)
        (run-pod cli-args))
    (let [native? (contains? cli-args "--native")]
      (pods/load-pod (if native?
                       (into ["./bb" "test-resources/pod.clj" "--run-as-pod"] cli-args)
                       (into ["lein" "bb" "test-resources/pod.clj" "--run-as-pod"] cli-args)))
      (require '[pod.test-pod])
      (if (contains? cli-args "--json")
        (do
          (debug "Running JSON test")
          (prn ((resolve 'pod.test-pod/assoc) {:a 1} :b 2)))
        (do
          (debug "Running synchronous add test")
          (prn ((resolve 'pod.test-pod/add-sync) 1 2 3))
          (debug "Running async stream test")
          (let [chan ((resolve 'pod.test-pod/range-stream) 1 10)]
            (loop []
              (when-let [x (async/<!! chan)]
                (debug "Received" x)
                (prn x)
                (recur))))
          (debug "Running exception test")
          (prn (try ((resolve 'pod.test-pod/error) 1 2 3)
                    (catch clojure.lang.ExceptionInfo e
                      (str (ex-message e) " / " (ex-data e)))))
          (debug "Running print test")
          ((resolve 'pod.test-pod/print) "hello" "print" "this" "debugging" "message")
          (debug "Running print-err test")
          ((resolve 'pod.test-pod/print-err) "hello" "print" "this" "error"))))))
