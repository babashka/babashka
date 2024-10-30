(ns babashka.impl.process
  {:no-doc true}
  (:require [babashka.process :as process]
            [babashka.process.pprint]
            [sci.core :as sci :refer [copy-var]]))

(def tns (sci/create-ns 'babashka.process nil))

(def defaults (sci/copy-var process/*defaults* tns))

(defn process* [& args]
  (binding [process/*defaults* @defaults]
    (apply process/process* args)))

(defn process [& args]
  (binding [process/*defaults* @defaults]
    (apply process/process args)))

(defn pb [& args]
  (binding [process/*defaults* @defaults]
    (apply process/pb args)))

(defn sh [& args]
  (binding [process/*defaults* @defaults]
    (apply process/sh args)))

(defn shell [& args]
  (binding [process/*defaults* @defaults]
    (apply process/shell args)))

(defn exec [& args]
  (binding [process/*defaults* @defaults]
    (apply process/exec args)))

(def process-namespace
  {'parse-args  (copy-var process/parse-args tns)
   'process*    (copy-var process/process* tns)
   'process     (copy-var process tns)
   'check       (copy-var process/check tns)
   'pb          (copy-var pb tns)
   'start       (copy-var process/start tns)
   'pipeline    (copy-var process/pipeline tns)
   '$           (copy-var process/$ tns)
   'sh          (copy-var sh tns)
   'tokenize    (copy-var process/tokenize tns)
   '*defaults*  defaults
   'destroy     (copy-var process/destroy tns)
   'destroy-tree (copy-var process/destroy-tree tns)
   'exec (copy-var exec tns)
   'shell (copy-var shell tns)
   'alive? (copy-var process/alive? tns)
   'concurrently (copy-var process/concurrently tns)})
