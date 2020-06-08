#!/usr/bin/env bb
(require '[clojure.java.io :as io])

(defn vim [file]
  (->
   (ProcessBuilder. ["vim" file])
   (.inheritIO)
   (.start)
   (.waitFor)))

(def readme
  (let [f (io/file "README.md")]
    (when (not (.exists f))
      (vim (.getPath f)))
    (slurp f)))

(println readme)
