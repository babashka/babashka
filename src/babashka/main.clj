(ns babashka.main
  {:no-doc true}
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str :refer [starts-with?]]
   [babashka.interpreter :as i])
  (:gen-class))

(set! *warn-on-reflection* true)

(defn read-edn [s]
  (edn/read-string
   {:readers *data-readers*}
   s))

(defn- parse-opts [options]
  (let [opts (loop [options options
                    opts-map {}
                    current-opt nil]
               (if-let [opt (first options)]
                 (if (starts-with? opt "--")
                   (recur (rest options)
                          (assoc opts-map opt [])
                          opt)
                   (recur (rest options)
                          (update opts-map current-opt conj opt)
                          current-opt))
                 opts-map))
        version (boolean (get opts "--version"))
        raw (boolean (get opts "--raw"))]
    {:version version
     :raw raw}))

(defn -main
  [& args]
  (let [{:keys [:version :raw]} (parse-opts args)]
    (cond version
      (println (str/trim (slurp (io/resource "BABASHKA_VERSION"))))
      :else
      (let [expr (if raw (second args) (first args))
            expr (read-edn expr)
            in (slurp *in*)
            in (if raw
                 (str/split in #"\s")
                 (read-edn (format "[%s]" in)))
            in (if (= 1 (count in)) (first in) in)]
        (prn (i/interpret expr in))))))

;;;; Scratch

(comment)
