(ns babashka.impl.process
  {:no-doc true}
  (:require [babashka.process :as process]
            [sci.core :as sci :refer [copy-var]]))

(def tns (sci/create-ns 'babashka.process nil))

(def escape-fn (sci/copy-var process/*default-escape-fn* tns))
(def shutdown-hook (sci/copy-var process/*default-shutdown-hook* tns))

(defn process [& args]
  (binding [process/*default-escape-fn* @escape-fn
            process/*default-shutdown-hook* @shutdown-hook]
    (apply process/process args)))

(defn pb [& args]
  (binding [process/*default-escape-fn* @escape-fn
            process/*default-shutdown-hook* @shutdown-hook]
    (apply process/pb args)))

(def process-namespace
  {'process     (copy-var process tns)
   'check       (copy-var process/check tns)
   'pb          (copy-var pb tns)
   'start       (copy-var process/start tns)
   'pipeline    (copy-var process/pipeline tns)
   '$           (copy-var process/$ tns)
   '*default-escape-fn* escape-fn
   '*default-shutdown-hook* shutdown-hook})
