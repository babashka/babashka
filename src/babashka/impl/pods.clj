(ns babashka.impl.pods
  {:no-doc true}
  (:refer-clojure :exclude [read])
  (:require [babashka.impl.bencode.core :as bencode]
            [cheshire.core :as cheshire]
            [clojure.core.async :as async]
            [clojure.edn :as edn]))

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

(defn processor [pod]
  (let [stdout (:stdout pod)
        format (:format pod)
        chans (:chans pod)
        read-fn (case format
                  :edn edn/read-string
                  :json #(cheshire/parse-string % true))]
    (try
      (loop []
        (let [reply (read stdout)
              id    (get reply "id")
              id    (bytes->string id)
              value? (contains? reply "value")
              value (when value? (get reply "value"))
              value (when value? (bytes->string value))
              value (when value? (read-fn value))
              status (get reply "status")
              status (set (map (comp keyword bytes->string) status))
              done? (contains? status :done)
              value (if (and (not value?) (contains? status :error))
                      (let [^String message
                            (or  (some-> (get reply "error")
                                         bytes->string)
                                 "")]
                        (Exception. message))
                      value)
              chan (get @chans id)]
          (when value (async/put! chan value))
          (when done? (async/close! chan)))
        (recur))
      (catch Exception e (prn e)))))

(defn invoke [pod pod-var args async?]
  (let [stream (:stdin pod)
        format (:format pod)
        chans (:chans pod)
        write-fn (case format
                   :edn pr-str
                   :json cheshire/generate-string)
        id (str (java.util.UUID/randomUUID))
        chan (async/chan)
        _ (swap! chans assoc id chan)
        _ (write stream {"id" id
                         "op" "invoke"
                         "var" (str pod-var)
                         "args" (write-fn args)})]
    (if async? chan ;; TODO: https://blog.jakubholy.net/2019/core-async-error-handling/
        (let [v (async/<!! chan)]
          (if (instance? Exception v)
            (throw v)
            v)))))

(defn load-pod
  ([ctx pod-spec] (load-pod ctx pod-spec nil))
  ([ctx pod-spec _opts]
   (let [pod-spec (if (string? pod-spec) [pod-spec] pod-spec)
         pb (ProcessBuilder. ^java.util.List pod-spec)
         _ (.redirectErrorStream pb true)
         p (.start pb)
         stdin (.getOutputStream p)
         stdout (.getInputStream p)
         stdout (java.io.PushbackInputStream. stdout)
         _ (add-shutdown-hook! #(.destroy p))
         _ (write stdin {"op" "describe"})
         reply (read stdout)
         format (-> (get reply "format") bytes->string keyword)
         pod {:process p
              :pod-spec pod-spec
              :stdin stdin
              :stdout stdout
              :chans (atom {})
              :format format}
         vars (get reply "vars")
         vars (map (fn [var]
                     (let [var (zipmap (map keyword (keys var))
                                       (map bytes->string (vals var)))
                           var (-> var
                                   (update :namespace symbol)
                                   (update :name symbol)
                                   (update :async #(Boolean/parseBoolean %)))]
                       var))
                   vars)
         env (:env ctx)]
     (swap! env
            (fn [env]
              (let [namespaces (:namespaces env)
                    namespaces (reduce (fn [acc v]
                                         (let [ns (:namespace v)
                                               name (:name v)
                                               sym (symbol (str ns) (str name))
                                               async? (:async v)
                                               f (fn [& args]
                                                   (let [res (invoke pod sym args async?)]
                                                     res))]
                                           (assoc-in acc [ns name] f)))
                                       namespaces
                                       vars)]
                (assoc env :namespaces namespaces))))
     (future (processor pod))
     vars)))

(def pods-namespace
  {'load-pod (with-meta load-pod
               {:sci.impl/op :needs-ctx})})
