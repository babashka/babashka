(ns user
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(defn bash [& args]
  (prn :args args)
  (prn :cmd  *command-line-args*)
  (-> (p/process ["bash" "-c" (str/join " " args)]
                 {:inherit true})
      p/check))
