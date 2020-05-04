(ns pod
  (:refer-clojure :exclude [read read-string])
  (:require [babashka.pods :as pods]
            [bencode.core :as bencode]
            [cheshire.core :as cheshire]
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
              :describe (do (write {"format" (if (= format :json)
                                               "json"
                                               "edn")
                                    "vars" [{"namespace" "pod.test-pod"
                                             "name" "add-sync"}
                                            {"namespace" "pod.test-pod"
                                             "name" "range-stream"
                                             "async" "true"}
                                            {"namespace" "pod.test-pod"
                                             "name" "assoc"}]})
                            (recur))
              :invoke (let [var (-> (get message "var")
                                    read-string
                                    symbol)
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
                            "id" id}))
                        (recur)))))))))

(let [cli-args (set *command-line-args*)]
  (if (contains? cli-args "--run-as-pod")
    (run-pod cli-args)
    (let [native? (contains? cli-args "--native")]
      (pods/load-pod (if native?
                       ["./bb" "test-resources/pod.clj" "--run-as-pod"]
                       ["lein" "bb" "test-resources/pod.clj" "--run-as-pod"]))
      (require '[pod.test-pod])
      (if (contains? cli-args "--json")
        (prn ((resolve 'pod.test-pod/assoc) {:a 1} :b 2))
        (do
          (prn ((resolve 'pod.test-pod/add-sync) 1 2 3))
          (let [chan ((resolve 'pod.test-pod/range-stream) 1 10)]
            (loop []
              (when-let [x (async/<!! chan)]
                (prn x)
                (recur)))))))))
