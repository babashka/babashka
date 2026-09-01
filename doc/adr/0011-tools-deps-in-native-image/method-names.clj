#!/usr/bin/env bb
;; Method names the vendored tools.deps sources call with (.name ...) or
;; (Class/name ...), one per line.
(require '[babashka.fs :as fs]
         '[clojure.string :as str])

(defn balanced
  "The text of the form starting at the ( at index i."
  [s i]
  (loop [j (inc i) depth 1]
    (if (zero? depth)
      (subs s i j)
      (recur (inc j) (case (.charAt s j) \( (inc depth) \) (dec depth) depth)))))

(defn dotdot-names
  "Every method name in the (.. x a (b arg) c) forms of s."
  [s]
  (let [m (re-matcher #"\(\.\.\s" s)]
    (loop [names []]
      (if (.find m)
        (let [form (balanced s (.start m))
              ;; drop the (.. and the receiver
              chain (str/replace-first form #"^\(\.\.\s+\S+" "")]
          (recur (into names (map second (re-seq #"(?<=[\s(])([a-z][A-Za-z0-9]*)\b" chain)))))
        names))))

(let [srcs (map #(slurp (str %)) (fs/glob "resources/src/babashka/clojure/tools" "**.clj"))
      ;; (.name x), and bare .name inside -> chains
      dot (mapcat #(map second (re-seq #"(?<![A-Za-z0-9_])\.([a-z][A-Za-z0-9]*)\b" %)) srcs)
      dotdot (mapcat dotdot-names srcs)
      stat (mapcat #(map second (re-seq #"\b[A-Z][A-Za-z0-9$]*/([a-z][A-Za-z0-9]*)\b" %)) srcs)]
  (doseq [m (into (sorted-set) (concat dot dotdot stat))]
    (println m)))
