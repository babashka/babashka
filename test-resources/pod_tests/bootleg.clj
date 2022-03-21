(ns pod-tests.bootleg
  (:require [pod.retrogradeorbit.bootleg.utils :as utils]))

(defn -main [& args]
  (-> [:div
       [:p "Test"]]
      (utils/convert-to :html)))
