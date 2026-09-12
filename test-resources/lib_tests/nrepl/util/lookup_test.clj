(ns nrepl.util.lookup-test
  (:require [clojure.test :refer :all]
            [nrepl.bencode :as bencode]
            [nrepl.test-helpers :refer [is+]]
            [nrepl.util.lookup :as l :refer [lookup]])
  (:import (java.io ByteArrayOutputStream)))

(deftest lookup-test
  (testing "special sym lookup"
    (is (not-empty (lookup 'clojure.core 'if))))

  (testing "fully qualified sym lookup"
    (is (not-empty (lookup 'nrepl.util.lookup 'clojure.core/map))))

  (testing "aliased sym lookup"
    (is (not-empty (lookup 'nrepl.util.lookup 'clojure.string/upper-case))))

  (testing "non-qualified lookup"
    (is (not-empty (lookup 'clojure.core 'map)))

    (is+ {:ns "clojure.core"
          :name "map"
          :arglists "([f] [f coll] [f c1 c2] [f c1 c2 c3] [f c1 c2 c3 & colls])"
          :arglists-str "([f] [f coll] [f c1 c2] [f c1 c2 c3] [f c1 c2 c3 & colls])"}
         (lookup 'nrepl.util.lookup 'map))

    (is+ {:ns "clojure.core"
          :name "map"
          :arglists "([f] [f coll] [f c1 c2] [f c1 c2 c3] [f c1 c2 c3 & colls])"
          :arglists-str "([f] [f coll] [f c1 c2] [f c1 c2 c3] [f c1 c2 c3 & colls])"}
         (lookup 'clojure.core 'map)))

  (testing "macro lookup"
    (is+ {:ns "clojure.core"
          :name "future"
          :macro "true"}
         (lookup 'clojure.core 'future)))

  (testing "special form lookup"
    (is+ {:ns "clojure.core"
          :name "let"
;; BB-TEST-PATCH sci exposes let as a macro, not a special form
          :macro "true"}
         (lookup 'clojure.core 'let)))

  (testing "Java sym lookup"
    (is+ nil (lookup 'clojure.core 'String))))

(defn- bencode-str
  "Bencode a thing and write it into a string."
  [thing]
  (let [out (ByteArrayOutputStream.)]
    (try
      (bencode/write-bencode out thing)
      (.toString out)
      (catch IllegalArgumentException ex
        (throw (ex-info (.getMessage ex) {:thing thing}))))))

(defn- lookup-public-vars
  "Look up every public var in all namespaces in the classpath and return the result as a set."
  []
  (transduce
   (comp
    (mapcat ns-publics)
    (map (comp meta val))
    (map #(lookup (.getName ^clojure.lang.Namespace (:ns %)) (:name %))))
   conj
   #{}
   (all-ns)))

(deftest bencode-test
  (doseq [m (lookup-public-vars)]
    (is ((comp string? not-empty) (bencode-str m)))))
