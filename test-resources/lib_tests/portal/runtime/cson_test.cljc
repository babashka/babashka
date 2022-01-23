(ns portal.runtime.cson-test
  (:require [clojure.test :refer [deftest are is]]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [cognitect.transit :as transit]
            [portal.bench :as b]
            [portal.runtime.cson :as cson])
  #?(:clj (:import [java.io ByteArrayOutputStream ByteArrayInputStream]
                   [java.util Date]
                   [java.util UUID])))

(defn- transit-read [^String string]
  #?(:clj  (-> string
               .getBytes
               ByteArrayInputStream.
               (transit/reader :json)
               transit/read)
     :cljs (transit/read (transit/reader :json) string)))

(defn- transit-write [value]
  #?(:clj (let [out (ByteArrayOutputStream. 1024)]
            (transit/write
             (transit/writer out :json {:transform transit/write-meta})
             value)
            (.toString out))
     :cljs (transit/write
            (transit/writer :json {:transform transit/write-meta})
            value)))

(defn pass [v]
  (cson/read (cson/write v)))

(deftest simple-values
  (are [value]
       (= value (pass value))
    nil
    0
    1.0
    #?(:clj 42N
       :cljs (when (exists? js/BigInt)
               (js/BigInt "42")))
    \newline
    true
    false
    'hello
    'hello/world
    :hello
    :hello/world
    ""
    "hello"
    "hello/world"))

(deftest escape-strings
  (are [value]
       (= value (pass value))
    "\n"
    "\""
    " \"hello\" "))

(deftest basic-collections
  (are [value]
       (= value (pass value))
    []
    [1 2 3]
    {}
    {:a :b}
    #{}
    #{1 2 3}
    '()
    (list 1 2 3)))

(def composite-value
  ['hello
   'hello/world
   '(1 2 3)
   ""
   3.14
   true
   false
   #inst "2021-04-07T22:43:59.393-00:00"
   #uuid "1d80bdbb-ab16-47b2-a8bd-068f94950248"
   nil
   1
   \h
   "data"
   {:hello/world :grep}
   #{1 2 3}])

(deftest composite-collections
  (are [value]
       (= value (pass value))
    [[[]]]
    #{#{#{}}}
    {{} {}}
    {[] []}
    {#{} #{}}
    {(list) (list)}
    (list [] #{} {})
    composite-value))

(deftest special-collections
  (are [value]
       (= value (pass value))
    (range 10)))

(deftest seq-collections
  (are [value]
       (= (seq value) (pass (seq value)))
    '(0)
    [0]
    #{0}
    {0 0}))

(def tagged
  [#?(:clj  (Date.)
      :cljs (js/Date.))
   #?(:clj  (UUID/randomUUID)
      :cljs (random-uuid))
   (tagged-literal 'tag :value)])

(deftest tagged-objects
  (doseq [value tagged]
    (is (= value (pass value)))))

(deftest metadata
  (doseq [value ['hello {} [] #{}]]
    (let [m     {:my :meta}
          value (with-meta value m)]
      (is (= m (meta (pass value)))))))

(deftest symbol-key-with-meta
  (let [m     {:a :b}
        value {(with-meta 'k m) 'v}]
    (is (= value (pass value)))
    (is (= m (meta (first (keys (pass value))))))))

(deftest cson-over-edn
  (is
   (-> composite-value
       (cson/write {:stringify pr-str})
       (cson/read  {:parse edn/read-string})
       (= composite-value))))

(def n 10000)
(def v composite-value)

(def edn
  {:parse     edn/read-string
   :stringify pr-str})

(comment
  (deftest rich-benchmark
    (b/simple-benchmark [] (transit-write v) n)
    (b/simple-benchmark [] (cson/write v edn) n)
    (b/simple-benchmark [] (cson/write v) n)

    (prn)

    (b/simple-benchmark
     [v (transit-write v)] (transit-read v) n)
    (b/simple-benchmark
     [v (cson/write v edn)] (cson/read v edn) n)
    (b/simple-benchmark
     [v (cson/write v)] (cson/read v) n)))

#?(:clj
   (deftest java-longs
     (is (= 1 (byte 1)  (pass (byte 1))))
     (is (= 1 (short 1) (pass (short 1))))
     (is (= 1 (int 1)   (pass (int 1))))
     (is (= 1 (long 1)  (pass (long 1))))
     (is (= 4611681620380904123 (pass 4611681620380904123)))))
