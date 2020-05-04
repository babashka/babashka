(ns babashka.impl.pods
  {:no-doc true}
  (:refer-clojure :exclude [read])
  (:require [babashka.impl.bencode.core :as bencode]
            [cheshire.core :as cheshire]
            [clojure.edn :as edn]))

(set! *warn-on-reflection* true)

(defn add-shutdown-hook! [^Runnable f]
  (-> (Runtime/getRuntime)
      (.addShutdownHook (Thread. f))))

(defn write [^java.io.OutputStream stream v]
  (bencode/write-bencode stream v)
  (.flush stream))

(defn read [stream]
  (bencode/read-bencode stream))

(defn bytes->string [^"[B" bytes]
  (String. bytes))

(def waiters (atom {}))

(defn deliver-value [waiter value done?]
  (if (instance? clojure.lang.IDeref waiter)
    (deliver waiter value)
    :core-async-todo))

(defn processor [pod]
  (let [stdout (:stdout pod)
        format (:format pod)
        read-fn (case format
                  :edn edn/read-string
                  :json #(cheshire/parse-string % true))]
    (loop []
      (println "reading")
      (try
        (let [reply (read stdout)
              _ (prn "reply" reply)
              id    (get reply "id")
              _ (prn "id" id)
              id    (bytes->string id)
              value (get reply "value")
              value (bytes->string value)
              value (read-fn value)
              _ (prn "val" value)
              status (get reply "status")
              status (set (map (comp keyword bytes->string) status))
              done? (contains? status :done)
              waiter (get @waiters id)]
          (deliver-value waiter value done?))
        (catch Exception e (prn e))))))

(defn invoke [pod pod-var args]
  (let [stream (:stdin pod)
        format (:format pod)
        write-fn (case format
                   :edn pr-str
                   :json cheshire/generate-string)
        id (str (java.util.UUID/randomUUID))
        prom (promise)
        _ (swap! waiters assoc id prom)
        _ (write stream {"id" id
                         "op" "invoke"
                         "var" (str pod-var)
                         "args" (write-fn args)})]
    @prom))

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
         pod {:process p
              :pod-spec pod-spec
              :stdin stdin
              :stdout stdout}
         _ (add-shutdown-hook! #(.destroy p))
         _ (write stdin {"op" "describe"})
         reply (read stdout)
         format (-> (get reply "format") bytes->string keyword)
         pod (assoc pod :format format)
         vars (get reply "vars")
         vars (map (fn [var]
                     (-> (zipmap (map keyword (keys var))
                                 (map bytes->string (vals var)))
                         (update :namespace symbol)
                         (update :name symbol)))
                   vars)
         env (:env ctx)]
     (swap! env
            (fn [env]
              (let [namespaces (:namespaces env)
                    namespaces (reduce (fn [acc v]
                                         (let [ns (:namespace v)
                                               name (:name v)
                                               sym (symbol (str ns) (str name))]
                                           (prn ns name)
                                           (assoc-in acc [ns name]
                                                     (fn [& args]
                                                       ;; (prn "calling" ns name args)
                                                       (invoke pod sym args)))))
                                       namespaces
                                       vars)]
                (assoc env :namespaces namespaces))))
     (println "spinning up worker")
     (future (processor pod))
     vars)))

(def pods-namespace
  {'load-pod (with-meta load-pod
               {:sci.impl/op :needs-ctx})})
