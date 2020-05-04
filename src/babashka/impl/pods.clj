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

(defn invoke [pod pod-var args]
  (let [stream (:stdin pod)
        format (:format pod)
        write-fn (case format
                   :edn pr-str
                   :json cheshire/generate-string)
        stdout (:stdout pod)
        _ (write stream {"op" "invoke"
                         "var" (str pod-var)
                         "args" (write-fn args)})
        reply (read stdout)
        value (get reply "value")
        value (bytes->string value)
        read-fn (case format
                  :edn edn/read-string
                  :json #(cheshire/parse-string % true))
        value (read-fn value)]
    value))

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
     vars)))

(def pods-namespace
  {'load-pod (with-meta load-pod
               {:sci.impl/op :needs-ctx})})
