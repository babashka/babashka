(ns babashka.proxy-test
  (:require
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.test :as test :refer [deftest is]]))

(defn bb [& args]
  (edn/read-string
   {:readers *data-readers*
    :eof nil}
   (apply test-utils/bb nil (map str args))))

(def code
  '(do
     (defn auto-deref
       "If value implements IDeref, deref it, otherwise return original."
       [x]
       (if (instance? clojure.lang.IDeref x)
         @x
         x))
     (defn proxy-deref-map
       {:added "1.0"}
       [m]
       (proxy [clojure.lang.APersistentMap]
           []
         (iterator []
           ::TODO)
         (containsKey [k] (contains? m k))
         (entryAt [k] (when (contains? m k) (proxy [clojure.lang.AMapEntry] []
                                              (key [] k)
                                              (val [] (auto-deref(get m k))))))
         (valAt ([k] (auto-deref (get m k)))
           ([k default] (auto-deref (get m k default))))
         (cons [v] (proxy-deref-map (conj m v)))
         (count [] (count m))
         (assoc [k v] (proxy-deref-map (assoc m k v)))
         (without [k] (proxy-deref-map (dissoc m k)))
         (seq [] (map (fn [[k v]](proxy [clojure.lang.AMapEntry] []
                                   (key [] k)
                                   (val [] (auto-deref (get m k))))) m))))
     (let [m (proxy-deref-map
              {:a (delay 1)
               :b (delay 2)
               :c 3})]
       [(:a m)
        (:b m)
        (:c m)
        (contains? m :c)
        (find m :c)
        (-> (conj m [:d (delay 5)])
            :d)
        (count m)
        (-> (assoc m :d (delay 5))
            :d)
        (-> (dissoc m :a)
            (contains? :a))
        (seq m)])))

(deftest APersistentMap-proxy-test
  (is (= [1 2 3 true [:c 3]
          5 3 5 false
          '([:a 1] [:b 2] [:c 3])]
         (bb code))))
