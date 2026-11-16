(ns orchard.profile
  "Very simplistic manual tracing profiler for individual functions."
  {:author "Oleksandr Yakushev"
   :added "0.33"}
  (:require [orchard.misc :as misc])
  (:import java.util.concurrent.locks.ReentrantLock
           java.util.Arrays))

;; The profiler works like following: for each profiled function, an entry in
;; `collected-timings` atom is created. Timings are stored as an array. Inside
;; each array, the first cell stores how many samples we have accumulated so
;; far. When the array becomes full, we grow it 2x until `max-sample-count` is
;; reached. At that point, new sample just overwrites a random old sample. The
;; mutable arrays are protected by a global `data-lock`.

(def ^:private ^:const max-sample-count (int (Math/pow 2 17)))
(def ^:private data-lock (ReentrantLock.))
(def ^:private collected-timings (atom {}))

(defn- assoc-and-get-array [k array]
  (get (swap! collected-timings assoc k array) k))

(defn- record-timing [k, ^long nanos]
  (misc/with-lock data-lock
    (let [^longs arr (or (get @collected-timings k)
                         ;; Initial array is 256 items long (1KB).
                         (assoc-and-get-array k (long-array 256)))
          alen (alength arr)
          n (aget arr 0) ;; First cell array stores number of samples.
          i (inc n)
          ;; Check if we've run out of free space in the array and still under
          ;; the max-sample-count. If so, grow the array.
          ^longs arr (if (and (>= i alen) (< alen max-sample-count))
                       (assoc-and-get-array k (Arrays/copyOf arr (* alen 2)))
                       arr)
          alen (alength arr)]
      (aset arr 0 i)
      (if (< i alen)
        (aset arr i nanos)
        ;; We're out of space and the array can't grow anymore, so we just write
        ;; to a random position.
        (aset arr (inc (rand-int (dec alen))) nanos)))))

(defn- resolve-var ^clojure.lang.Var [v]
  (if (var? v) v (resolve v)))

(defn- wrap-profiled [var raw-fn]
  (fn profiling-wrapper [& args]
    (let [nano-now (System/nanoTime)
          val (apply raw-fn args)
          elapsed (- (System/nanoTime) nano-now)]
      (record-timing var elapsed)
      val)))

;;;; Calculations

(defn- standard-deviation [^longs arr, ^double mean]
  (let [sum (areduce arr i sum 0.0 (+ sum (Math/pow (- mean (aget arr i)) 2.0)))]
    (Math/sqrt (/ sum (max (dec (alength arr)) 1)))))

(defn- entry-stats [var, ^longs samples]
  (let [count  (aget samples 0)
        n      (min (dec (alength samples)) count)
        sorted (doto (Arrays/copyOfRange samples 1 (inc n)) Arrays/sort)
        sum    (areduce sorted i sum 0 (+ sum (aget sorted i)))
        mean   (double (/ sum n))]
    (array-map ;; Using array-map to enforce key order.
     :name var
     :n count
     :mean mean
     :std (standard-deviation sorted mean)
     :sum sum
     :min (aget sorted 0)
     :max (aget sorted (dec n))
     :med (aget sorted (int (/ n 2)))
     :samples (vec sorted))))

(defn- format-duration [nanos]
  (cond (> nanos 1e9) (format "%.1f s" (/ nanos 1e9))
        (> nanos 1e6) (format "%.0f ms" (/ nanos 1e6))
        (> nanos 1e3) (format "%.0f us" (/ nanos 1e3))
        :else (format "%.0f ns" (double nanos))))

(defn- format-stats-for-inspector [stats-map]
  ;; Prettify results: attach units to timings, convert strings to symbols to
  ;; avoid quotes when this data will be displayed in the inspector.
  (-> (reduce #(update %1 %2 (comp symbol format-duration)) stats-map
              [:mean :sum :min :max :med])
      (update :std #(symbol (str "±" (format-duration %))))))

;;;; Public API

(def ^:private profiled-vars (atom #{}))
(def ^:private profiled-nses (atom #{}))

(defn profilable?
  "Return true if `v` contains a profilable function."
  [v]
  (let [v (resolve-var v)]
    (and (ifn? @v) (not (:macro (meta v))))))

(defn profiled?
  "Return true if `v` is already profiled."
  [v]
  (let [v (resolve-var v)]
    (contains? (meta v) ::profiled)))

(defn profile-var
  "If the specified Var holds a function, its contents is replaced with a version
  wrapped in a profiling call. Can be undone with `unprofile-var`."
  [v]
  (let [v (resolve-var v)]
    (when (and (profilable? v) (not (profiled? v)))
      (let [raw-fn @v]
        (swap! profiled-vars conj v)
        (alter-var-root v #(wrap-profiled v %))
        (alter-meta! v assoc ::profiled raw-fn)
        v))))

(defn unprofile-var
  "Reverses the effect of `profile-var` for the given Var, replacing the profiled
  function with the original version."
  [v]
  (let [v (resolve-var v)
        f (::profiled (meta v))]
    (when f
      (alter-var-root v (constantly (::profiled (meta v))))
      (alter-meta! v dissoc ::profiled)
      (swap! profiled-vars disj v)
      v)))

(defn profile-ns
  "Profile all Vars in the given namespace. Can be undone with `unprofile-ns`."
  [ns]
  (let [ns (the-ns ns)]
    (when-not ('#{clojure.core orchard.profile} (.name ns))
      (->> (ns-interns ns)
           vals
           #_(filter (comp fn? var-get)) ;; BB-PATCH user/*input* is interned as a non-var
(filter #(and (var? %) (fn? (var-get %))))
           (run! profile-var))
      (swap! profiled-nses conj ns))))

(defn unprofile-ns
  "Unprofile all Vars in the given namespace."
  [ns]
  (let [ns (the-ns ns)]
    (->> (ns-interns ns)
         vals
         #_(filter (comp fn? var-get)) ;; BB-PATCH user/*input* is interned as a non-var
(filter #(and (var? %) (fn? (var-get %))))
         (run! unprofile-var))
    (swap! profiled-nses disj ns)))

(defn toggle-profile-ns
  "Profile vars in the given namespace if it's not profiled yet, otherwise undo
  the profiling. Return true if profiling did happen."
  [ns]
  (let [ns (the-ns ns)]
    (if (contains? @profiled-nses ns)
      (do (unprofile-ns ns)
          false)
      (do (profile-ns ns)
          true))))

(defn unprofile-all
  "Reverses the effect of profiling for all already profiled vars and namespaces."
  []
  (run! unprofile-ns @profiled-nses)
  (run! unprofile-var @profiled-vars))

(defn summary
  "Returns a map where keys are the profiled function vars, and values are maps
  with the profiling stats."
  []
  (misc/with-lock data-lock
    (into {} (map (fn [[var samples]] [var (entry-stats var samples)]))
          @collected-timings)))

(defn summary-for-inspector
  "Return profiling results as a list of stats maps, optimized to be viewed with
  `orchard.inspect`."
  []
  (sort-by #(str (:name %)) (vals (misc/update-vals format-stats-for-inspector (summary)))))

(defn clear
  "Clears all profiling results."
  []
  (misc/with-lock data-lock
    (reset! collected-timings {})))
