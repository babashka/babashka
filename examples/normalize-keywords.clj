(ns normalize-keywords
  (:require [babashka.pods :as pods]
            [rewrite-clj.node :as node]
            [rewrite-clj.zip :as z]))

(pods/load-pod 'clj-kondo/clj-kondo "2022.11.02")

(require '[pod.borkdude.clj-kondo :as clj-kondo])

(def code (first *command-line-args*))

(def findings
  (->> (with-in-str code
         (clj-kondo/run! {:lint [code]
                          :config {:output {:analysis {:keywords true}}}}))
       :analysis
       :keywords
       (filter (some-fn :alias :auto-resolved))))

(defn finding->keyword [{:keys [:ns :name]}]
  (keyword (str ns) (str name)))

(defn remove-locs [zloc findings]
  (loop [zloc zloc
         findings (seq findings)]
    (if findings
      (let [{:keys [:row :col] :as finding} (first findings)
            node (z/node zloc)
            m (meta node)]
        (if (and (= row (:row m))
                 (= col (:col m)))
          (let [k (finding->keyword finding)
                zloc (z/replace zloc (node/coerce k))]
            (recur zloc (next findings)))
          (recur (z/next zloc) findings)))
      (println (str (z/root zloc))))))

(remove-locs (z/of-file code) findings)
