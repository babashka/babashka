(ns cider.nrepl.middleware.version
  "Return version info of the CIDER-nREPL middleware itself."
  (:require
   [cider.nrepl.version :as version]
   [cider.nrepl.middleware.util :refer [respond-to]]))

(defn handle-version [handler msg]
  (if (= (:op msg) "cider-version")
    (respond-to msg {:status :done, :cider-version version/version})
    (handler msg)))
