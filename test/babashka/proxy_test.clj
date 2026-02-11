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
     (require '[clojure.core.protocols])
     (require '[clojure.datafy :as d])
     (defn auto-deref
       "If value implements IDeref, deref it, otherwise return original."
       [x]
       (if (instance? clojure.lang.IDeref x)
         @x
         x))
     (defn proxy-deref-map
       {:added "1.0"}
       [m]
       (proxy [clojure.lang.APersistentMap clojure.lang.IMeta clojure.lang.IObj clojure.core.protocols.Datafiable]
           []
         (iterator []
           ::TODO)
         (containsKey [k] (contains? m k))
         (entryAt [k] (when (contains? m k) (proxy [clojure.lang.AMapEntry] []
                                              (key [] k)
                                              (val [] (auto-deref (get m k))))))
         (valAt ([k] (auto-deref (get m k)))
           ([k default] (auto-deref (get m k default))))
         (cons [v] (proxy-deref-map (conj m v)))
         (count [] (count m))
         (assoc [k v] (proxy-deref-map (assoc m k v)))
         (without [k] (proxy-deref-map (dissoc m k)))
         (seq [] (map (fn [[k v]](proxy [clojure.lang.AMapEntry] []
                                   (key [] k)
                                   (val [] (auto-deref (get m k))))) m))
         (withMeta [md] (proxy-deref-map (with-meta m md)))
         (meta [] (meta m))

         (datafy [] {:datafied true})))
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
        (seq m)
        (meta (with-meta m {:a 1}))
        (d/datafy m)
        (instance? clojure.lang.APersistentMap m)
        (instance? java.io.FilenameFilter m)
        ,])))

(require 'clojure.pprint)

(deftest APersistentMap-proxy-test
  (is (= [1 2 3 true [:c 3]
          5 3 5 false
          '([:a 1] [:b 2] [:c 3])
          {:a 1}
          {:datafied true}
          true
          false]
         (bb (with-out-str (clojure.pprint/pprint code))))))

(deftest proxy-with-protocol-test
  (is (= {:method "hello world"
          :satisfies true}
         (bb '(do
                (defprotocol MyProto
                  (my-method [this x]))
                (def obj
                  (proxy [clojure.lang.APersistentMap clojure.lang.IMeta clojure.lang.IObj MyProto] []
                    (my-method [x] (str "hello " x))
                    (valAt ([k] (get {:a 1} k)) ([k d] (get {:a 1} k d)))
                    (iterator [] (.iterator {}))
                    (containsKey [k] (contains? {:a 1} k))
                    (entryAt [k] nil)
                    (count [] 1)
                    (assoc [k v] nil)
                    (without [k] nil)
                    (seq [] nil)
                    (equiv [other] false)
                    (empty [] nil)
                    (cons [elem] nil)
                    (meta [] nil)
                    (withMeta [m] nil)))
                {:method (my-method obj "world")
                 :satisfies (satisfies? MyProto obj)})))))

(deftest PipedInputStream-PipedOutputStream-proxy-test
  (is (= {:available 1
          :read-result -1
          :byte-read 10
          :array-read '(0 0 0 0 0 0 10 0 0 0 0 0 0 0 0 0)
          :instance? true}
         (bb (with-out-str
               (clojure.pprint/pprint
                '(let [ins (proxy [java.io.PipedInputStream] []
                            (available [] 1)
                            (close [] nil)
                            (read
                              ([] 10)
                              ([byte-arr off len]
                               (aset byte-arr off (byte 10))
                               -1))
                            (receive [b]
                              nil))
                       arr (byte-array 16)
                       ]
                   {:available (.available ins)
                    :read-result (.read ins arr 6 2)
                    :byte-read (.read ins)
                    :array-read (seq arr)
                    :instance? (instance? java.io.PipedInputStream ins)}))))))

  (is (= {:instance? true
          :arr '(1 2 3 4 5 0 0 0)
          :arr2 '(10)}
         (bb (with-out-str
               (clojure.pprint/pprint
                '(let [arr (byte-array 8)
                       arr2 (byte-array 1)
                       outs (proxy [java.io.PipedOutputStream] []
                             (close [] nil)
                             (connect [sink] nil)
                             (flush [] nil)
                             (write
                               ([b] (aset arr2 0 (byte b)))
                               ([byte-arr off len]
                                (doseq [n (range len)]
                                  (aset arr n (aget byte-arr (+ off n)))))))]
                   (.write outs (int 10))
                   (.write outs (byte-array [0 0 0 1 2 3 4 5 0 0 0]) 3 5)
                   {:instance? (instance? java.io.PipedOutputStream outs)
                    :arr (seq arr)
                    :arr2 (seq arr2)})))))))
