(ns babashka.impl.pods
  {:no-doc true}
  (:refer-clojure :exclude [read])
  (:require [babashka.impl.bencode.core :as bencode]
            [cheshire.core :as cheshire]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [sci.core :as sci]))

(set! *warn-on-reflection* true)

(defn add-shutdown-hook! [^Runnable f]
  (-> (Runtime/getRuntime)
      (.addShutdownHook (Thread. f))))

(defn write [^java.io.OutputStream stream v]
  (locking stream
    (bencode/write-bencode stream v)
    (.flush stream)))

(defn read [stream]
  (bencode/read-bencode stream))

(defn bytes->string [^"[B" bytes]
  (String. bytes))

(defn get-string [m k]
  (-> (get m k)
      bytes->string))

(defn processor [_ctx pod]
  (let [stdout (:stdout pod)
        format (:format pod)
        chans (:chans pod)
        read-fn (case format
                  :edn edn/read-string
                  :json #(cheshire/parse-string-strict % true))]
    (try
      (loop []
        (let [reply (read stdout)
              id    (get reply "id")
              id    (bytes->string id)
              value* (find reply "value")
              value (some-> value*
                            second
                            bytes->string
                            read-fn)
              status (get reply "status")
              status (set (map (comp keyword bytes->string) status))
              done? (contains? status :done)
              error? (contains? status :error)
              value (if error?
                      (let [message (or (some-> (get reply "ex-message")
                                                bytes->string)
                                        "")
                            data (or (some-> (get reply "ex-data")
                                             bytes->string
                                             read-fn)
                                     {})]
                        (ex-info message data))
                      value)
              chan (get @chans id)
              out (some-> (get reply "out")
                          bytes->string)
              err (some-> (get reply "err")
                          bytes->string)]
          (when (or value* error?) (async/put! chan value))
          (when (or done? error?) (async/close! chan))
          (when out (binding [*out* @sci/out]
                      (println out)))
          (when err (binding [*out* @sci/err]
                      (println err))))
        (recur))
      (catch Exception e
        (binding [*out* @sci/err]
          (prn e))))))

(defn next-id []
  (str (java.util.UUID/randomUUID)))

(defn invoke [pod pod-var args async?]
  (let [stream (:stdin pod)
        format (:format pod)
        chans (:chans pod)
        write-fn (case format
                   :edn pr-str
                   :json cheshire/generate-string)
        id (next-id)
        chan (async/chan)
        _ (swap! chans assoc id chan)
        _ (write stream {"id" id
                         "op" "invoke"
                         "var" (str pod-var)
                         "args" (write-fn args)})]
    (if async? chan ;; TODO: https://blog.jakubholy.net/2019/core-async-error-handling/
        (let [v (async/<!! chan)]
          (if (instance? Throwable v)
            (throw v)
            v)))))

(defn load-pod
  ([ctx pod-spec] (load-pod ctx pod-spec nil))
  ([ctx pod-spec _opts]
   (let [pod-spec (if (string? pod-spec) [pod-spec] pod-spec)
         pb (ProcessBuilder. ^java.util.List pod-spec)
         _ (.redirectError pb java.lang.ProcessBuilder$Redirect/INHERIT)
         p (.start pb)
         stdin (.getOutputStream p)
         stdout (.getInputStream p)
         stdout (java.io.PushbackInputStream. stdout)
         _ (add-shutdown-hook!
            (fn []
              (write stdin {"op" "shutdown"
                            "id" (next-id)})
              (.waitFor p)))
         _ (write stdin {"op" "describe"
                         "id" (next-id)})
         reply (read stdout)
         format (-> (get reply "format") bytes->string keyword)
         pod {:process p
              :pod-spec pod-spec
              :stdin stdin
              :stdout stdout
              :chans (atom {})
              :format format}
         pod-namespaces (get reply "namespaces")
         vars-fn (fn [ns-name-str vars]
                   (reduce
                    (fn [m var]
                      (let [name (get-string var "name")
                            async? (some-> (get var "async")
                                           bytes->string
                                           #(Boolean/parseBoolean %))
                            name-sym (symbol name)
                            sym (symbol ns-name-str name)]
                        (assoc m name-sym (fn [& args]
                                            (let [res (invoke pod sym args async?)]
                                              res)))))
                    {}
                    vars))
         env (:env ctx)]
     (swap! env
            (fn [env]
              (let [namespaces (:namespaces env)
                    namespaces
                    (reduce (fn [namespaces namespace]
                              (let [name-str (-> namespace (get "name") bytes->string)
                                    name-sym (symbol name-str)
                                    vars (get namespace "vars")
                                    vars (vars-fn name-str vars)]
                                (assoc namespaces name-sym vars)))
                            namespaces
                            pod-namespaces)]
                (assoc env :namespaces namespaces))))
     (sci/future (processor ctx pod))
     ;; TODO: we could return the entire describe map here
     nil)))

(def pods-namespace
  {'load-pod (with-meta load-pod
               {:sci.impl/op :needs-ctx})})
