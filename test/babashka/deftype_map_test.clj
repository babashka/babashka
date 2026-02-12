(ns babashka.deftype-map-test
  (:require
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.test :as test :refer [deftest is testing]]))

(defn bb [& args]
  (edn/read-string
   {:readers *data-readers*
    :eof nil}
   (apply test-utils/bb nil (map str args))))

;; Helper: wraps test body with a standard map deftype definition.
;; The deftype wraps an inner map `m` and delegates all map operations.
(defn with-simple-map-deftype
  "Returns a (do ...) form that defines SimpleMap deftype + ->SimpleMap,
   then executes `body-form`."
  [body-form]
  (list 'do
        '(declare ->SimpleMap)
        '(deftype SimpleMap [m]
           clojure.lang.ILookup
           (valAt [_ k] (get m k))
           (valAt [_ k nf] (get m k nf))
           clojure.lang.Seqable
           (seq [_] (seq m))
           clojure.lang.IPersistentMap
           (assoc [_ k v] (->SimpleMap (assoc m k v)))
           (assocEx [_ k v] (->SimpleMap (assoc m k v)))
           (without [_ k] (->SimpleMap (dissoc m k)))
           clojure.lang.Associative
           (containsKey [_ k] (contains? m k))
           (entryAt [_ k] (when (contains? m k)
                             (clojure.lang.MapEntry. k (get m k))))
           clojure.lang.IPersistentCollection
           (equiv [_ other] (= m other))
           (count [_] (count m))
           (empty [_] (->SimpleMap {}))
           (cons [_ o] (->SimpleMap (conj m o)))
           java.lang.Iterable
           (iterator [_] (.iterator ^java.lang.Iterable m)))
        body-form))

(deftest basic-map-deftype-test
  (testing "deftype with full map interfaces works as a persistent map"
    (is (= {:get-a 1
            :keyword-b 2
            :seq [[:a 1] [:b 2] [:c 3]]
            :count 3
            :assoc-d [[:a 1] [:b 2] [:c 3] [:d 4]]
            :dissoc-b [[:a 1] [:c 3]]
            :contains-a true
            :contains-z false
            :keys [:a :b :c]
            :vals [1 2 3]}
           (bb (with-simple-map-deftype
                 '(let [mm (->SimpleMap (array-map :a 1 :b 2 :c 3))]
                    {:get-a (get mm :a)
                     :keyword-b (:b mm)
                     :seq (vec (seq mm))
                     :count (count mm)
                     :assoc-d (vec (seq (assoc mm :d 4)))
                     :dissoc-b (vec (seq (dissoc mm :b)))
                     :contains-a (contains? mm :a)
                     :contains-z (contains? mm :z)
                     :keys (vec (keys mm))
                     :vals (vec (vals mm))})))))))

(deftest valAt-not-found-test
  (testing "2-arity valAt with not-found value"
    (is (= {:found 1
            :not-found :default
            :nil-val nil
            :nil-not-found :nope}
           (bb (with-simple-map-deftype
                 '(let [mm (->SimpleMap {:a 1 :b nil})]
                    {:found (get mm :a :default)
                     :not-found (get mm :z :default)
                     :nil-val (get mm :b)
                     :nil-not-found (get mm :z :nope)})))))))

(deftest empty-map-test
  (testing "operations on empty map deftype"
    (is (= {:count 0
            :seq nil
            :empty-from-full 0
            :assoc-on-empty 1}
           (bb (with-simple-map-deftype
                 '(let [e (->SimpleMap {})
                        full (->SimpleMap {:a 1 :b 2})
                        emptied (empty full)]
                    {:count (count e)
                     :seq (seq e)
                     :empty-from-full (count emptied)
                     :assoc-on-empty (count (assoc e :x 1))})))))))

(deftest destructuring-test
  (testing "map destructuring on map deftype"
    (is (= {:keys-dest [1 2]
            :or-default 99
            :as-map true}
           (bb (with-simple-map-deftype
                 '(let [mm (->SimpleMap {:a 1 :b 2})
                        {:keys [a b c] :or {c 99} :as whole} mm]
                    {:keys-dest [a b]
                     :or-default c
                     :as-map (= whole {:a 1 :b 2})})))))))

(deftest into-merge-test
  (testing "into and merge with map deftype"
    (is (= {:into-map {:a 1 :b 2 :c 3}
            :merge-result {:a 10 :b 2 :c 3}
            :into-from-map [[:a 1] [:b 2]]}
           (bb (with-simple-map-deftype
                 '(let [mm (->SimpleMap (array-map :a 1 :b 2))]
                    {:into-map (into {} (assoc mm :c 3))
                     :merge-result (into {} (merge mm {:a 10 :c 3}))
                     :into-from-map (vec (seq (into (->SimpleMap {}) {:a 1 :b 2})))})))))))

(deftest multiple-fields-test
  (testing "deftype with multiple fields"
    (is (= {:lookup 1
            :field-cache {:a 1}
            :field-q [:a :b]
            :count 1}
           (bb '(do
                  (declare ->MultiField)
                  (deftype MultiField [cache q]
                    clojure.lang.ILookup
                    (valAt [_ k] (get cache k))
                    (valAt [_ k nf] (get cache k nf))
                    clojure.lang.Seqable
                    (seq [_] (seq cache))
                    clojure.lang.IPersistentMap
                    (assoc [_ k v] (->MultiField (assoc cache k v) (conj q k)))
                    (assocEx [_ k v] (->MultiField (assoc cache k v) (conj q k)))
                    (without [_ k] (->MultiField (dissoc cache k) q))
                    clojure.lang.Associative
                    (containsKey [_ k] (contains? cache k))
                    (entryAt [_ k] (when (contains? cache k)
                                     (clojure.lang.MapEntry. k (get cache k))))
                    clojure.lang.IPersistentCollection
                    (equiv [_ other] (= cache other))
                    (count [_] (count cache))
                    (empty [_] (->MultiField {} []))
                    (cons [_ o] (->MultiField (conj cache o) q))
                    java.lang.Iterable
                    (iterator [_] (.iterator ^java.lang.Iterable cache)))

                  (let [mf (->MultiField {:a 1} [:a :b])]
                    {:lookup (:a mf)
                     :field-cache (.cache mf)
                     :field-q (.q mf)
                     :count (count mf)})))))))

(deftest instance-checks-test
  (testing "instance? checks on map deftype"
    (is (= {:persistent-map true
            :associative true
            :ilookup true
            :seqable true
            :counted true
            :iterable true
            :ifn true
            :ihash-eq true
            :reversible true
            :map-iterable true}
           (bb (with-simple-map-deftype
                 '(let [mm (->SimpleMap {:a 1})]
                    {:persistent-map (instance? clojure.lang.IPersistentMap mm)
                     :associative (instance? clojure.lang.Associative mm)
                     :ilookup (instance? clojure.lang.ILookup mm)
                     :seqable (instance? clojure.lang.Seqable mm)
                     :counted (instance? clojure.lang.Counted mm)
                     :iterable (instance? java.lang.Iterable mm)
                     :ifn (instance? clojure.lang.IFn mm)
                     :ihash-eq (instance? clojure.lang.IHashEq mm)
                     :reversible (instance? clojure.lang.Reversible mm)
                     :map-iterable (instance? clojure.lang.IMapIterable mm)})))))))

(deftest minimum-match-test
  (testing "deftype declaring only IPersistentMap (minimum required) matches"
    (is (= {:get 1 :count 1}
           (bb '(do
                  (declare ->MinMap)
                  (deftype MinMap [m]
                    clojure.lang.IPersistentMap
                    (assoc [_ k v] (->MinMap (assoc m k v)))
                    (assocEx [_ k v] (->MinMap (assoc m k v)))
                    (without [_ k] (->MinMap (dissoc m k)))
                    clojure.lang.ILookup
                    (valAt [_ k] (get m k))
                    (valAt [_ k nf] (get m k nf))
                    clojure.lang.Seqable
                    (seq [_] (seq m))
                    clojure.lang.Associative
                    (containsKey [_ k] (contains? m k))
                    (entryAt [_ k] (when (contains? m k)
                                     (clojure.lang.MapEntry. k (get m k))))
                    clojure.lang.IPersistentCollection
                    (equiv [_ other] (= m other))
                    (count [_] (count m))
                    (empty [_] (->MinMap {}))
                    (cons [_ o] (->MinMap (conj m o)))
                    java.lang.Iterable
                    (iterator [_] (.iterator ^java.lang.Iterable m)))

                  (let [mm (->MinMap {:a 1})]
                    {:get (:a mm) :count (count mm)})))))))

(deftest map-as-function-test
  (testing "map deftype as IFn — (m :key) and (m :key default)"
    (is (= {:invoke 1
            :invoke-missing nil}
           (bb (with-simple-map-deftype
                 '(let [mm (->SimpleMap {:a 1})]
                    {:invoke (mm :a)
                     :invoke-missing (mm :z)})))))))

(deftest find-entry-at-test
  (testing "find returns MapEntry, nil for missing key"
    (is (= {:found [:a 1]
            :missing nil}
           (bb (with-simple-map-deftype
                 '(let [mm (->SimpleMap {:a 1 :b 2})]
                    {:found (vec (find mm :a))
                     :missing (find mm :z)})))))))

(deftest cons-with-vector-test
  (testing "conj with vector pair and MapEntry"
    (is (= {:conj-vec [[:a 1] [:b 2]]
            :conj-map [[:a 1] [:b 2]]}
           (bb (with-simple-map-deftype
                 '(let [mm (->SimpleMap (array-map :a 1))]
                    {:conj-vec (vec (seq (conj mm [:b 2])))
                     :conj-map (vec (seq (conj mm (first {:b 2}))))})))))))

(deftest syntax-quoted-method-names-test
  (testing "macro with syntax-quote produces ns-qualified method names — SciMap strips them"
    (is (= {:get 1 :count 1}
           (bb '(do
                  (defmacro make-map [name fields & body]
                    `(do
                       (declare ~(symbol (str "->" name)))
                       (deftype ~name ~fields
                         clojure.lang.ILookup
                         (~'valAt [~'_ ~'k] (get ~(first fields) ~'k))
                         (~'valAt [~'_ ~'k ~'nf] (get ~(first fields) ~'k ~'nf))
                         clojure.lang.Seqable
                         (~'seq [~'_] (seq ~(first fields)))
                         clojure.lang.IPersistentMap
                         (~'assoc [~'_ ~'k ~'v]
                           (~(symbol (str "->" name)) (assoc ~(first fields) ~'k ~'v)))
                         (~'assocEx [~'_ ~'k ~'v]
                           (~(symbol (str "->" name)) (assoc ~(first fields) ~'k ~'v)))
                         (~'without [~'_ ~'k]
                           (~(symbol (str "->" name)) (dissoc ~(first fields) ~'k)))
                         clojure.lang.Associative
                         (~'containsKey [~'_ ~'k] (contains? ~(first fields) ~'k))
                         (~'entryAt [~'_ ~'k]
                           (when (contains? ~(first fields) ~'k)
                             (clojure.lang.MapEntry. ~'k (get ~(first fields) ~'k))))
                         clojure.lang.IPersistentCollection
                         (~'equiv [~'_ ~'other] (= ~(first fields) ~'other))
                         (~'count [~'_] (count ~(first fields)))
                         (~'empty [~'_] (~(symbol (str "->" name)) {}))
                         (~'cons [~'_ ~'o] (~(symbol (str "->" name)) (conj ~(first fields) ~'o)))
                         java.lang.Iterable
                         (~'iterator [~'_] (.iterator ~(with-meta (first fields)
                                                         {:tag 'java.lang.Iterable}))))))

                  (make-map MacroMap [m])

                  (let [mm (->MacroMap {:a 1})]
                    {:get (:a mm)
                     :count (count mm)})))))))

(deftest map-deftype-with-meta-test
  (testing "deftype with map interfaces + IMeta/IObj"
    (is (= {:val 1
            :meta {:tag :test}
            :with-meta-val 1
            :with-meta-meta {:tag :new}}
           (bb '(do
                  (declare ->MetaMap)
                  (deftype MetaMap [m]
                    clojure.lang.ILookup
                    (valAt [_ k] (get m k))
                    (valAt [_ k nf] (get m k nf))
                    clojure.lang.Seqable
                    (seq [_] (seq m))
                    clojure.lang.IPersistentMap
                    (assoc [_ k v] (->MetaMap (assoc m k v)))
                    (assocEx [_ k v] (->MetaMap (assoc m k v)))
                    (without [_ k] (->MetaMap (dissoc m k)))
                    clojure.lang.Associative
                    (containsKey [_ k] (contains? m k))
                    (entryAt [_ k] (when (contains? m k)
                                     (clojure.lang.MapEntry. k (get m k))))
                    clojure.lang.IPersistentCollection
                    (equiv [_ other] (= m other))
                    (count [_] (count m))
                    (empty [_] (->MetaMap {}))
                    (cons [_ o] (->MetaMap (conj m o)))
                    java.lang.Iterable
                    (iterator [_] (.iterator ^java.lang.Iterable m))
                    clojure.lang.IMeta
                    (meta [_] (meta m))
                    clojure.lang.IObj
                    (withMeta [_ md] (->MetaMap (with-meta m md))))

                  (let [mm (->MetaMap (with-meta {:a 1} {:tag :test}))
                        mm2 (with-meta mm {:tag :new})]
                    {:val (:a mm)
                     :meta (meta mm)
                     :with-meta-val (:a mm2)
                     :with-meta-meta (meta mm2)})))))))

(deftest map-deftype-with-protocol-test
  (testing "deftype with map interfaces + custom protocol"
    (is (= {:lookup 1
            :proto-result "cached:a"
            :satisfies true}
           (bb '(do
                  (defprotocol MyProto
                    (my-method [this k]))
                  (declare ->ProtoMap)
                  (deftype ProtoMap [m]
                    clojure.lang.ILookup
                    (valAt [_ k] (get m k))
                    (valAt [_ k nf] (get m k nf))
                    clojure.lang.Seqable
                    (seq [_] (seq m))
                    clojure.lang.IPersistentMap
                    (assoc [_ k v] (->ProtoMap (assoc m k v)))
                    (assocEx [_ k v] (->ProtoMap (assoc m k v)))
                    (without [_ k] (->ProtoMap (dissoc m k)))
                    clojure.lang.Associative
                    (containsKey [_ k] (contains? m k))
                    (entryAt [_ k] (when (contains? m k)
                                     (clojure.lang.MapEntry. k (get m k))))
                    clojure.lang.IPersistentCollection
                    (equiv [_ other] (= m other))
                    (count [_] (count m))
                    (empty [_] (->ProtoMap {}))
                    (cons [_ o] (->ProtoMap (conj m o)))
                    java.lang.Iterable
                    (iterator [_] (.iterator ^java.lang.Iterable m))
                    MyProto
                    (my-method [_ k] (str "cached:" (name k))))

                  (let [mm (->ProtoMap {:a 1})]
                    {:lookup (:a mm)
                     :proto-result (my-method mm :a)
                     :satisfies (satisfies? MyProto mm)})))))))

(deftest map-deftype-field-access-test
  (testing "field access via .fieldName on map deftype"
    (is (= {:field-val {:a 1 :b 2}
            :lookup 1}
           (bb '(do
                  (declare ->FieldMap)
                  (deftype FieldMap [cache]
                    clojure.lang.ILookup
                    (valAt [_ k] (get cache k))
                    (valAt [_ k nf] (get cache k nf))
                    clojure.lang.Seqable
                    (seq [_] (seq cache))
                    clojure.lang.IPersistentMap
                    (assoc [_ k v] (->FieldMap (assoc cache k v)))
                    (assocEx [_ k v] (->FieldMap (assoc cache k v)))
                    (without [_ k] (->FieldMap (dissoc cache k)))
                    clojure.lang.Associative
                    (containsKey [_ k] (contains? cache k))
                    (entryAt [_ k] (when (contains? cache k)
                                     (clojure.lang.MapEntry. k (get cache k))))
                    clojure.lang.IPersistentCollection
                    (equiv [_ other] (= cache other))
                    (count [_] (count cache))
                    (empty [_] (->FieldMap {}))
                    (cons [_ o] (->FieldMap (conj cache o)))
                    java.lang.Iterable
                    (iterator [_] (.iterator ^java.lang.Iterable cache)))

                  (let [mm (->FieldMap {:a 1 :b 2})]
                    {:field-val (.cache mm)
                     :lookup (:a mm)})))))))

(deftest map-deftype-equality-test
  (testing "map deftype equality with regular maps"
    (is (= {:eq-map true
            :eq-other-deftype true
            :not-eq false}
           (bb (with-simple-map-deftype
                 '(let [mm1 (->SimpleMap {:a 1 :b 2})
                        mm2 (->SimpleMap {:a 1 :b 2})]
                    {:eq-map (= mm1 {:a 1 :b 2})
                     :eq-other-deftype (= mm1 mm2)
                     :not-eq (= mm1 {:a 1})})))))))

(deftest map-deftype-reduce-kv-test
  (testing "reduce-kv works on map deftype (default seq-based)"
    (is (= [[:a 1] [:b 2]]
           (bb (with-simple-map-deftype
                 '(let [mm (->SimpleMap (array-map :a 1 :b 2))]
                    (reduce-kv (fn [acc k v] (conj acc [k v])) [] mm))))))))

(deftest map-deftype-rseq-test
  (testing "rseq with custom implementation"
    (is (= {:seq [[:a 1] [:b 2] [:c 3]]
            :rseq [[:c 3] [:b 2] [:a 1]]
            :reversible true}
           (bb '(do
                  (declare ->OrdMap)
                  (deftype OrdMap [m]
                    clojure.lang.ILookup
                    (valAt [_ k] (get m k))
                    (valAt [_ k nf] (get m k nf))
                    clojure.lang.Seqable
                    (seq [_] (seq m))
                    clojure.lang.IPersistentMap
                    (assoc [_ k v] (->OrdMap (assoc m k v)))
                    (assocEx [_ k v] (->OrdMap (assoc m k v)))
                    (without [_ k] (->OrdMap (dissoc m k)))
                    clojure.lang.Associative
                    (containsKey [_ k] (contains? m k))
                    (entryAt [_ k] (when (contains? m k)
                                     (clojure.lang.MapEntry. k (get m k))))
                    clojure.lang.IPersistentCollection
                    (equiv [_ other] (= m other))
                    (count [_] (count m))
                    (empty [_] (->OrdMap {}))
                    (cons [_ o] (->OrdMap (conj m o)))
                    java.lang.Iterable
                    (iterator [_] (.iterator ^java.lang.Iterable m))
                    clojure.lang.Reversible
                    (rseq [_] (reverse (seq m))))

                  (let [mm (->OrdMap (array-map :a 1 :b 2 :c 3))]
                    {:seq (vec (seq mm))
                     :rseq (vec (rseq mm))
                     :reversible (reversible? mm)})))))))

(deftest map-deftype-rseq-default-test
  (testing "rseq default (reverse of seq) when Reversible not declared"
    (is (= {:seq [[:a 1] [:b 2]]
            :rseq [[:b 2] [:a 1]]
            :reversible true}
           (bb (with-simple-map-deftype
                 '(let [mm (->SimpleMap (array-map :a 1 :b 2))]
                    {:seq (vec (seq mm))
                     :rseq (vec (rseq mm))
                     :reversible (reversible? mm)})))))))

(deftest map-deftype-toString-test
  (testing "custom toString on map deftype"
    (is (= "<my-map>"
           (bb '(do
                  (declare ->StrMap)
                  (deftype StrMap [m]
                    clojure.lang.ILookup
                    (valAt [_ k] (get m k))
                    (valAt [_ k nf] (get m k nf))
                    clojure.lang.Seqable
                    (seq [_] (seq m))
                    clojure.lang.IPersistentMap
                    (assoc [_ k v] (->StrMap (assoc m k v)))
                    (assocEx [_ k v] (->StrMap (assoc m k v)))
                    (without [_ k] (->StrMap (dissoc m k)))
                    clojure.lang.Associative
                    (containsKey [_ k] (contains? m k))
                    (entryAt [_ k] (when (contains? m k)
                                     (clojure.lang.MapEntry. k (get m k))))
                    clojure.lang.IPersistentCollection
                    (equiv [_ other] (= m other))
                    (count [_] (count m))
                    (empty [_] (->StrMap {}))
                    (cons [_ o] (->StrMap (conj m o)))
                    java.lang.Iterable
                    (iterator [_] (.iterator ^java.lang.Iterable m))
                    Object
                    (toString [_] "<my-map>"))

                  (str (->StrMap {:a 1}))))))))

(deftest map-deftype-rejects-novel-interface-test
  (testing "deftype with novel interface (not inherent to APersistentMap) is rejected"
    (is (thrown-with-msg?
         Exception #"not supported"
         (bb '(do
                (deftype BadMap [m]
                  clojure.lang.IPersistentMap
                  (assoc [_ k v] nil)
                  (assocEx [_ k v] nil)
                  (without [_ k] nil)
                  clojure.lang.ILookup
                  (valAt [_ k] nil)
                  clojure.lang.Seqable
                  (seq [_] nil)
                  clojure.lang.Associative
                  (containsKey [_ k] false)
                  (entryAt [_ k] nil)
                  clojure.lang.IPersistentCollection
                  (equiv [_ other] false)
                  (count [_] 0)
                  (empty [_] nil)
                  (cons [_ o] nil)
                  java.lang.Iterable
                  (iterator [_] nil)
                  clojure.lang.Sorted
                  (comparator [_] nil)
                  (entryKey [_ entry] nil)
                  (seq [_ ascending] nil)
                  (seqFrom [_ key ascending] nil))))))))

(deftest map-deftype-hasheq-test
  (testing "custom hasheq on map deftype"
    (is (= 42
           (bb '(do
                  (declare ->HashMap2)
                  (deftype HashMap2 [m]
                    clojure.lang.ILookup
                    (valAt [_ k] (get m k))
                    (valAt [_ k nf] (get m k nf))
                    clojure.lang.Seqable
                    (seq [_] (seq m))
                    clojure.lang.IPersistentMap
                    (assoc [_ k v] (->HashMap2 (assoc m k v)))
                    (assocEx [_ k v] (->HashMap2 (assoc m k v)))
                    (without [_ k] (->HashMap2 (dissoc m k)))
                    clojure.lang.Associative
                    (containsKey [_ k] (contains? m k))
                    (entryAt [_ k] (when (contains? m k)
                                     (clojure.lang.MapEntry. k (get m k))))
                    clojure.lang.IPersistentCollection
                    (equiv [_ other] (= m other))
                    (count [_] (count m))
                    (empty [_] (->HashMap2 {}))
                    (cons [_ o] (->HashMap2 (conj m o)))
                    java.lang.Iterable
                    (iterator [_] (.iterator ^java.lang.Iterable m))
                    clojure.lang.IHashEq
                    (hasheq [_] 42))

                  (.hasheq (->HashMap2 {:a 1}))))))))
