(ns orchard.inspect.analytics
  "Submodule of Orchard Inspector for getting quick insights about the inspected
  data. A \"Metabase\" for Orchard/CIDER Inspector."
  (:refer-clojure :exclude [bounded-count])
  (:require
   [clojure.string :as str])
  (:import
   (clojure.lang RT)
   (java.util List Map Set)))

;; To keep execution time under control, only calculate analytics for the first
;; 100k elements.
(def ^:dynamic *size-cutoff* 100000)

(defn- non-nil-hmap [& keyvals]
  (->> (partition 2 keyvals)
       (keep #(when (some? (second %)) (vec %)))
       (into {})))

(defn- *frequencies [coll only-duplicates?]
  (let [freqs (->> coll
                   (eduction (comp (take *size-cutoff*)))
                   frequencies)
        freqs (if only-duplicates?
                (into {} (filter (fn [[_ v]] (>= v 2))) freqs)
                freqs)
        cmp #(let [res (compare (freqs %1) (freqs %2))]
               (if (zero? res)
                 ;; If values are identical, compare hashes of their keys.
                 ;; Hashes because keys themselves might not be comparable.
                 (compare (hash %1) (hash %2))
                 (- res)))]
    ;; Turn the result in a map that is sorted by descending value.
    (into (sorted-map-by cmp) freqs)))

#_(definline ^:private inc-if [val condition]
  `(cond-> ~val ~condition inc)) ;; BB-PATCH sci has no definline
(defn- inc-if [val condition] (cond-> val condition inc))

(defn- count-pred [pred limit ^Iterable coll]
  (let [it (RT/iter coll)]
    (loop [i 0, n 0]
      (if (and (< i limit) (.hasNext it))
        (let [x (.next it)]
          (recur (inc i) (inc-if n (pred x))))
        [n (if (pos? i) (/ n i) 0.0)]))))

(defn- bounded-count [limit coll]
  (first (count-pred (constantly true) limit coll)))

(defn- list-of-tuples?
  "Heuristic-based: an sequence is a list of tuples if at least 20 items of the
  first 100, or at least 30% of it, are maps with < 20 values."
  [coll]
  (and (instance? List coll)
       (let [[n ratio] (count-pred #(and (vector? %) (< (count %) 20)) 100 coll)]
         (or (> n 20) (> ratio 0.3)))))

(defn- list-of-records?
  "Heuristic-based: a sequence is a list of 'records' if at least 20 items of the
  first 100, or at least 30% of it, are vectors with size < 20."
  [coll]
  (and (instance? List coll)
       (let [[n ratio] (count-pred #(and (map? %) (< (count %) 20)) 100 coll)]
         (or (> n 20) (> ratio 0.3)))))

(defn- numbers-stats [^Iterable coll]
  (let [it (.iterator coll)]
    (loop [i 0, hi nil, lo nil, zeros 0, n 0, sum 0.0]
      (if (and (< i *size-cutoff*) (.hasNext it))
        (let [x (.next it)]
          (if (number? x)
            (recur (inc i)
                   (if (nil? hi) x (max hi x))
                   (if (nil? lo) x (min lo x))
                   (inc-if zeros (zero? x))
                   (inc n)
                   (+ sum (double x)))
            (recur (inc i) hi lo zeros n sum)))
        (when (> n 0)
          {:n n, :zeros zeros, :max hi, :min lo, :mean (/ sum n)})))))

(def ^:private ^java.nio.charset.CharsetEncoder ascii-enc
  (.newEncoder (java.nio.charset.Charset/forName "US-ASCII")))

(defn- strings-stats [^Iterable coll]
  (let [it (.iterator coll)]
    (loop [i 0, n 0, blank 0, ascii 0, hi nil, lo nil, sum 0]
      (if (and (< i *size-cutoff*) (.hasNext it))
        (let [x (.next it)]
          (if (string? x)
            (let [len (count x)]
              (recur (inc i)
                     (inc n)
                     (inc-if blank (str/blank? x))
                     (inc-if ascii (.canEncode ascii-enc ^String x))
                     (if (nil? hi) len (max hi len))
                     (if (nil? lo) len (min lo len))
                     (+ sum len)))
            (recur (inc i) n blank ascii hi lo sum)))
        (when (> n 0)
          {:n n, :blank blank, :ascii ascii, :max-len hi, :min-len lo, :avg-len (float (/ sum n))})))))

(defn- colls-stats [^Iterable coll]
  (let [it (.iterator coll)]
    (loop [i 0, n 0, empty 0, hi nil, lo nil, sum 0]
      (if (and (< i *size-cutoff*) (.hasNext it))
        (let [x (.next it)]
          (if (or (instance? java.util.Collection x) (some-> x class .isArray))
            (let [size (count x)]
              (recur (inc i)
                     (inc n)
                     (inc-if empty (empty? x))
                     (if (nil? hi) size (max hi size))
                     (if (nil? lo) size (min lo size))
                     (+ sum size)))
            (recur (inc i) n empty hi lo sum)))
        (when (> n 0)
          {:n n, :empty empty, :max-size hi, :min-size lo, :avg-size (float (/ sum n))})))))

(defn- basic-list-stats [coll show-count?]
  (when (or (instance? List coll) (instance? Set coll))
    (let [cnt (bounded-count *size-cutoff* coll)]
      (non-nil-hmap
       :cutoff? (when (and show-count? (>= cnt *size-cutoff*)) true)
       :count (when show-count? cnt)
       :types (*frequencies (map type coll) false)
       :duplicates (not-empty (*frequencies coll true))
       :numbers (numbers-stats coll)
       :strings (strings-stats coll)
       :collections (colls-stats coll)))))

(defn- keyvals-stats [coll]
  (when (instance? Map coll)
    (let [cnt (bounded-count *size-cutoff* coll)]
      (non-nil-hmap
       :cutoff? (when (>= cnt *size-cutoff*) true)
       :count cnt
       :keys (basic-list-stats (vec (keys coll)) false)
       :values (basic-list-stats (vec (vals coll)) false)))))

(defn- tuples-stats [^Iterable coll]
  (when (list-of-tuples? coll)
    (let [cnt (bounded-count *size-cutoff* coll)
          all (into [] (take *size-cutoff*) coll)
          longest (->> all
                       (keep #(when (instance? List %) (bounded-count 20 %)))
                       (apply max)
                       (min 20))]
      (non-nil-hmap
       :cutoff? (when (>= cnt *size-cutoff*) true)
       :count cnt
       :types (*frequencies (map type coll) false)
       :tuples (mapv (fn [i]
                       (basic-list-stats
                        (mapv #(when (vector? %) (nth % i nil)) all)
                        false))
                     (range longest))))))

(defn- records-stats [^Iterable coll]
  (when (list-of-records? coll)
    (let [cnt (bounded-count *size-cutoff* coll)
          ks (set (mapcat keys coll))]
      (non-nil-hmap
       :cutoff? (when (>= cnt *size-cutoff*) true)
       :count cnt
       :types (*frequencies (map type coll) false)
       :by-key (into {}
                     (for [k ks]
                       (let [kcoll (mapv #(get % k) coll)]
                         [k (basic-list-stats kcoll false)])))))))

(defn analytics
  "Return various analytical data about `object`. Supports the following data
  types with different amount of insights:
  - lists of numbers
  - lists of strings
  - lists of tuples
  - lists of 'records' (maps with same keys)
  - lists of arbitrary collections
  - arbitrary key-value maps"
  [object]
  (let [object (if (some-> (class object) (.isArray))
                 ;; Convert arrays into vectors to simplify analytics for them.
                 (vec object)
                 object)]
    (or (tuples-stats object)
        (records-stats object)
        (keyvals-stats object)
        (basic-list-stats object true))))

(defn can-analyze?
  "Simple heuristic: we currently only analyze collections (but most of them)."
  [object]
  (or (instance? List object)
      (instance? Map object)
      (instance? Set object)
      (some-> (class object) (.isArray))))
