(ns babashka.impl.process
  {:no-doc true}
  (:require [babashka.process :as process]
            [sci.core :as sci :refer [copy-var]]))

(def tns (sci/create-ns 'babashka.process nil))

(def process-namespace
  {'process  (copy-var process/process tns)
   'check    (copy-var process/check tns)
   'pb       (copy-var process/pb tns)
   'pipeline (copy-var process/pipeline tns)
   '$        (copy-var process/$ tns)})
