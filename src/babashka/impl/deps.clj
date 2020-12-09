(ns babashka.impl.deps
  (:require [babashka.impl.classpath :as cp]
            [borkdude.deps :as deps]
            [clojure.string :as str]
            [sci.core :as sci]))

(def dns (sci/create-ns 'dns nil))

;;;; merge deps.edn files

(defn- merge-or-replace
  "If maps, merge, otherwise replace"
  [& vals]
  (when (some identity vals)
    (reduce (fn [ret val]
              (if (and (map? ret) (map? val))
                (merge ret val)
                (or val ret)))
            nil vals)))

(defn merge-edns
  "Merge multiple deps edn maps from left to right into a single deps edn map."
  [deps-edn-maps]
  (apply merge-with merge-or-replace (remove nil? deps-edn-maps)))

;;;; end merge edn files

;; We are optimizing for the 1-file script with deps scenario where people can
;; call this function to include e.g. {:deps {medley/medley
;; {:mvn/version "1.3.3"}}}. Optionally they can include aliases, to modify the
;; classpath.
(defn add-deps
  ([deps-map] (add-deps deps-map nil))
  ([deps-map {:keys [:aliases]}]
   (let [args ["-Spath" "-Sdeps" (str deps-map)]
         args (cond-> args
                aliases (conj (str "-A:" (str/join ":" aliases))))
         cp (with-out-str (apply deps/-main args))]
     (cp/add-classpath cp))))

(defn clojure
  ([deps-map & args]
   (let [args (into ["-Sdeps" (str deps-map)] args)]
     (apply deps/-main args))))

(def deps-namespace
  {'add-deps (sci/copy-var add-deps dns)
   'clojure (sci/copy-var clojure dns)})
