(ns my.as-alias-ns
  (:require [my.impl1 :as-alias al]))

(defn alias-fn []
  #::al{:bar "a bar"})
