(ns babashka.impl.clojure.test.check
  {:no-doc true}
  (:require [clojure.test.check.random :as r]
            [sci.core :as sci]))

(def next-rng
  "Returns a random-number generator. Successive calls should return
  independent results."
  (let [a (atom (delay (r/make-java-util-splittable-random (System/currentTimeMillis))))
        thread-local
        (proxy [ThreadLocal] []
          (initialValue []
            (first (r/split (swap! a #(second (r/split (force %))))))))]
    (fn []
      (let [rng (.get thread-local)
            [rng1 rng2] (r/split rng)]
        (.set thread-local rng2)
        rng1))))

(defn make-random
  "Given an optional Long seed, returns an object that satisfies the
  IRandom protocol."
  ([] (next-rng))
  ([seed] (r/make-java-util-splittable-random seed)))

(alter-var-root #'r/next-rng (constantly next-rng))
(alter-var-root #'r/make-random (constantly make-random))

(require '[clojure.test.check.generators :as gen])

(def gen-ns (sci/create-ns 'clojure.test.check.generators nil))

(def generators-namespace
  {'choose (sci/copy-var gen/choose gen-ns)
   'sample (sci/copy-var gen/sample gen-ns)})
