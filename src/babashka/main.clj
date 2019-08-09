(ns babashka.main
  {:no-doc true}
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str :refer [starts-with?]]
   [babashka.interpreter :as i])
  (:gen-class))

(set! *warn-on-reflection* true)

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
        version (boolean (get opts "--version"))]
    {:version version}))

(defn -main
  [& args]
  (let [{:keys [:version]} (parse-opts args)]
    (cond version
      (println (str/trim (slurp (io/resource "BABASHKA_VERSION"))))
      :else
      (let [expr (edn/read-string (first args))
            in (slurp *in*)
            edn (edn/read-string in)]
        (prn (i/interpret expr edn))))))

;;;; Scratch

(comment)
