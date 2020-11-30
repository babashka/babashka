(ns babashka.protocols-test
  (:require  [babashka.test-utils :refer [bb]]
             [clojure.edn :as edn]
             [clojure.test :as t :refer [deftest is]]))

(deftest safe-datafy
  (is (apply = 100000 (map count (edn/read-string (bb nil "
(ns safe-datafy
  (:require [clojure.core.protocols :as p]
            [clojure.datafy :as df]))

(def max-sequence-length 100000)

(defn infinite-sequence-chunking [s]
  (cond-> (take max-sequence-length s)
    (< max-sequence-length (bounded-count (inc max-sequence-length) s))
    (vary-meta merge (meta s) {:nextjournal.seq/truncated? true})))

(extend-protocol p/Datafiable
  clojure.lang.Cons
  (df/datafy [s] (infinite-sequence-chunking s))
  clojure.lang.LazySeq
  (df/datafy [s] (infinite-sequence-chunking s))
  clojure.lang.Iterate
  (df/datafy [s] (infinite-sequence-chunking s))
  clojure.lang.Repeat
  (df/datafy [s] (infinite-sequence-chunking s))
  clojure.lang.Cycle
  (df/datafy [s] (infinite-sequence-chunking s)))

(defn safe-datafy [x]
  (let [dtf (cond-> x
              (not (instance? clojure.lang.IRef x))
              df/datafy)]
    (cond-> dtf
      (instance? clojure.lang.IObj dtf)
      (vary-meta (fn [m] (merge (dissoc m :clojure.datafy/class :clojure.datafy/obj)
                                (meta x)))))))

[(doall (safe-datafy (range)))
 (doall (safe-datafy (repeat 1)))
 (doall (safe-datafy (cons -1 (range))))]"))))))
