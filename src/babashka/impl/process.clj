(ns babashka.impl.process
  {:no-doc true}
  (:require [babashka.process :as process]
            [sci.core :as sci :refer [copy-var]]))

(def tns (sci/create-ns 'babashka.process nil))

(def escape-fn (sci/copy-var process/*escape-fn* tns))

(defn process [& args]
  (binding [process/*escape-fn* @escape-fn]
    (apply process/process args)))

(defn pb [& args]
  (binding [process/*escape-fn* @escape-fn]
    (apply process/pb args)))

(def process-namespace
  {'process     (copy-var process tns)
   'check       (copy-var process/check tns)
   'pb          (copy-var pb tns)
   'pipeline    (copy-var process/pipeline tns)
   '$           (copy-var process/$ tns)
   '*escape-fn* escape-fn})
