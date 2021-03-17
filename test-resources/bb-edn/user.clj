(ns user
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(defn bash [& args]
  (-> (p/process ["bash" "-c" (str/join " " args)]
                 {:inherit true})
      p/check))
