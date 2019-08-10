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
                 (if (starts-with? opt "-")
                   (recur (rest options)
                          (assoc opts-map opt [])
                          opt)
                   (recur (rest options)
                          (update opts-map current-opt conj opt)
                          current-opt))
                 opts-map))
        version (boolean (get opts "--version"))
        raw-in (boolean (or (get opts "--raw")
                            (get opts "-i")
                            (get opts "-io")))
        raw-out (boolean (or (get opts "-o")
                             (get opts "-io")))
        println? (boolean (get opts "--println"))]
    {:version version
     :raw-in raw-in
     :raw-out raw-out
     :println? println?}))

(defn -main
  [& args]
  (let [{:keys [:version :raw-in :raw-out :println?]} (parse-opts args)]
    (cond version
      (println (str/trim (slurp (io/resource "BABASHKA_VERSION"))))
      :else
      (let [expr (last args)
            expr (read-edn (-> expr
                               (str/replace "#(" "#f(")
                               (str/replace "#\"" "#r\"")))
            in (slurp *in*)
            ;; _ (prn in)
            in (if raw-in
                 (str/split in #"\n")
                 (read-edn in))
            ;; _ (prn in)
            res (try (i/interpret expr in)
                     (catch Exception e
                       (binding [*out* *err*]
                         (println (.getMessage e)))
                       (System/exit 1)))]
        (if raw-out
          (if (coll? res)
            (doseq [l res]
              (println l))
            (println res))
          ((if println? println? prn) res))))))

;;;; Scratch

(comment
  )
