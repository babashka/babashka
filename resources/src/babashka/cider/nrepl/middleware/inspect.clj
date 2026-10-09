(ns cider.nrepl.middleware.inspect
  "Stand-in for cider-nrepl's inspector middleware. Uses babashka's inspector."
  (:require [babashka.nrepl.impl.cider :as cider]))

(defn swap-inspector!
  "Applies f and args to the session's inspector and returns the new one."
  [msg f & args]
  (apply @#'cider/swap-inspector! msg f args))
