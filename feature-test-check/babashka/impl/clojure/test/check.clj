(ns babashka.impl.clojure.test.check
  {:no-doc true}
  (:require [clojure.test.check.random :as random]
            [sci.core :as sci]))

(def next-rng
  "Returns a random-number generator. Successive calls should return
  independent results."
  (let [a (atom (delay (random/make-java-util-splittable-random (System/currentTimeMillis))))
        thread-local
        (proxy [ThreadLocal] []
          (initialValue []
            (first (random/split (swap! a #(second (random/split (force %))))))))]
    (fn []
      (let [rng (.get thread-local)
            [rng1 rng2] (random/split rng)]
        (.set thread-local rng2)
        rng1))))

(defn make-random
  "Given an optional Long seed, returns an object that satisfies the
  IRandom protocol."
  ([] (next-rng))
  ([seed] (random/make-java-util-splittable-random seed)))

(alter-var-root #'random/next-rng (constantly next-rng))
(alter-var-root #'random/make-random (constantly make-random))

(def r-ns (sci/create-ns 'clojure.test.check.random nil))

#_(doseq [k (sort (keys (ns-publics 'clojure.test.check.random)))]
    (println (str "'" k) (format "(sci/copy-var random/%s r-ns)" k)))

(def random-namespace
  {'make-java-util-splittable-random (sci/copy-var random/make-java-util-splittable-random r-ns)
   'make-random (sci/copy-var random/make-random r-ns)
   'rand-double (sci/copy-var random/rand-double r-ns)
   'rand-long (sci/copy-var random/rand-long r-ns)
   'split (sci/copy-var random/split r-ns)
   'split-n (sci/copy-var random/split-n r-ns)})

(require '[clojure.test.check.generators :as gen])

(def gen-ns (sci/create-ns 'clojure.test.check.generators nil))

#_(doseq [k (sort (keys (ns-publics 'clojure.test.check.generators)))]
    (println (str "'" k) (format "(sci/copy-var gen/%s gen-ns)" k)))

(def generators-namespace
  {'->Generator (sci/copy-var gen/->Generator gen-ns)
   'any (sci/copy-var gen/any gen-ns)
   'any-equatable (sci/copy-var gen/any-equatable gen-ns)
   'any-printable (sci/copy-var gen/any-printable gen-ns)
   'any-printable-equatable (sci/copy-var gen/any-printable-equatable gen-ns)
   'big-ratio (sci/copy-var gen/big-ratio gen-ns)
   'bind (sci/copy-var gen/bind gen-ns)
   'boolean (sci/copy-var gen/boolean gen-ns)
   'byte (sci/copy-var gen/byte gen-ns)
   'bytes (sci/copy-var gen/bytes gen-ns)
   'call-gen (sci/copy-var gen/call-gen gen-ns)
   'char (sci/copy-var gen/char gen-ns)
   'char-alpha (sci/copy-var gen/char-alpha gen-ns)
   'char-alpha-numeric (sci/copy-var gen/char-alpha-numeric gen-ns)
   'char-alphanumeric (sci/copy-var gen/char-alphanumeric gen-ns)
   'char-ascii (sci/copy-var gen/char-ascii gen-ns)
   'choose (sci/copy-var gen/choose gen-ns)
   'container-type (sci/copy-var gen/container-type gen-ns)
   'double (sci/copy-var gen/double gen-ns)
   'double* (sci/copy-var gen/double* gen-ns)
   'elements (sci/copy-var gen/elements gen-ns)
   'fmap (sci/copy-var gen/fmap gen-ns)
   'frequency (sci/copy-var gen/frequency gen-ns)
   'gen-bind (sci/copy-var gen/gen-bind gen-ns)
   'gen-fmap (sci/copy-var gen/gen-fmap gen-ns)
   'gen-pure (sci/copy-var gen/gen-pure gen-ns)
   'generate (sci/copy-var gen/generate gen-ns)
   'generator? (sci/copy-var gen/generator? gen-ns)
   'hash-map (sci/copy-var gen/hash-map gen-ns)
   'int (sci/copy-var gen/int gen-ns)
   'keyword (sci/copy-var gen/keyword gen-ns)
   'keyword-ns (sci/copy-var gen/keyword-ns gen-ns)
   'large-integer (sci/copy-var gen/large-integer gen-ns)
   'large-integer* (sci/copy-var gen/large-integer* gen-ns)
   'lazy-random-states (sci/copy-var gen/lazy-random-states gen-ns)
   'let (sci/copy-var gen/let gen-ns)
   'list (sci/copy-var gen/list gen-ns)
   'list-distinct (sci/copy-var gen/list-distinct gen-ns)
   'list-distinct-by (sci/copy-var gen/list-distinct-by gen-ns)
   'make-size-range-seq (sci/copy-var gen/make-size-range-seq gen-ns)
   'map (sci/copy-var gen/map gen-ns)
   'map->Generator (sci/copy-var gen/map->Generator gen-ns)
   'nat (sci/copy-var gen/nat gen-ns)
   'neg-int (sci/copy-var gen/neg-int gen-ns)
   'no-shrink (sci/copy-var gen/no-shrink gen-ns)
   'not-empty (sci/copy-var gen/not-empty gen-ns)
   'one-of (sci/copy-var gen/one-of gen-ns)
   'pos-int (sci/copy-var gen/pos-int gen-ns)
   'ratio (sci/copy-var gen/ratio gen-ns)
   'recursive-gen (sci/copy-var gen/recursive-gen gen-ns)
   'resize (sci/copy-var gen/resize gen-ns)
   'return (sci/copy-var gen/return gen-ns)
   's-neg-int (sci/copy-var gen/s-neg-int gen-ns)
   's-pos-int (sci/copy-var gen/s-pos-int gen-ns)
   'sample (sci/copy-var gen/sample gen-ns)
   'sample-seq (sci/copy-var gen/sample-seq gen-ns)
   'scale (sci/copy-var gen/scale gen-ns)
   'set (sci/copy-var gen/set gen-ns)
   'shrink-2 (sci/copy-var gen/shrink-2 gen-ns)
   'shuffle (sci/copy-var gen/shuffle gen-ns)
   'simple-type (sci/copy-var gen/simple-type gen-ns)
   'simple-type-equatable (sci/copy-var gen/simple-type-equatable gen-ns)
   'simple-type-printable (sci/copy-var gen/simple-type-printable gen-ns)
   'simple-type-printable-equatable (sci/copy-var gen/simple-type-printable-equatable gen-ns)
   'size-bounded-bigint (sci/copy-var gen/size-bounded-bigint gen-ns)
   'sized (sci/copy-var gen/sized gen-ns)
   'small-integer (sci/copy-var gen/small-integer gen-ns)
   'sorted-set (sci/copy-var gen/sorted-set gen-ns)
   'string (sci/copy-var gen/string gen-ns)
   'string-alpha-numeric (sci/copy-var gen/string-alpha-numeric gen-ns)
   'string-alphanumeric (sci/copy-var gen/string-alphanumeric gen-ns)
   'string-ascii (sci/copy-var gen/string-ascii gen-ns)
   'such-that (sci/copy-var gen/such-that gen-ns)
   'symbol (sci/copy-var gen/symbol gen-ns)
   'symbol-ns (sci/copy-var gen/symbol-ns gen-ns)
   'tuple (sci/copy-var gen/tuple gen-ns)
   'uuid (sci/copy-var gen/uuid gen-ns)
   'vector (sci/copy-var gen/vector gen-ns)
   'vector-distinct (sci/copy-var gen/vector-distinct gen-ns)
   'vector-distinct-by (sci/copy-var gen/vector-distinct-by gen-ns)})

(require '[clojure.test.check.rose-tree :as rose-tree])

(def rose-ns (sci/create-ns 'clojure.test.check.rose-tree nil))

#_(doseq [k (sort (keys (ns-publics 'clojure.test.check.rose-tree)))]
    (println (str "'" k) (format "(sci/copy-var rose-tree/%s rose-ns)" k)))

(def rose-tree-namespace
  {'->RoseTree (sci/copy-var rose-tree/->RoseTree rose-ns)
   'bind (sci/copy-var rose-tree/bind rose-ns)
   'children (sci/copy-var rose-tree/children rose-ns)
   'collapse (sci/copy-var rose-tree/collapse rose-ns)
   'filter (sci/copy-var rose-tree/filter rose-ns)
   'fmap (sci/copy-var rose-tree/fmap rose-ns)
   'join (sci/copy-var rose-tree/join rose-ns)
   'make-rose (sci/copy-var rose-tree/make-rose rose-ns)
   'permutations (sci/copy-var rose-tree/permutations rose-ns)
   'pure (sci/copy-var rose-tree/pure rose-ns)
   'remove (sci/copy-var rose-tree/remove rose-ns)
   'root (sci/copy-var rose-tree/root rose-ns)
   'seq (sci/copy-var rose-tree/seq rose-ns)
   'shrink (sci/copy-var rose-tree/shrink rose-ns)
   'shrink-vector (sci/copy-var rose-tree/shrink-vector rose-ns)
   'zip (sci/copy-var rose-tree/zip rose-ns)})

(require '[clojure.test.check.properties :as properties])

(def p-ns (sci/create-ns 'clojure.test.check.properties nil))

#_(doseq [k (sort (keys (ns-publics 'clojure.test.check.properties)))]
    (println (str "'" k) (format "(sci/copy-var properties/%s p-ns)" k)))

(def properties-namespace
  {'->ErrorResult (sci/copy-var properties/->ErrorResult p-ns)
   'for-all (sci/copy-var properties/for-all p-ns)
   'for-all* (sci/copy-var properties/for-all* p-ns)
   'map->ErrorResult (sci/copy-var properties/map->ErrorResult p-ns)})

(require '[clojure.test.check :as tc])

(def tc-ns (sci/create-ns 'clojure.test.check nil))

#_(doseq [k (sort (keys (ns-publics 'clojure.test.check)))]
    (println (str "'" k) (format "(sci/copy-var tc/%s p-ns)" k)))

(def test-check-namespace
  {'quick-check (sci/copy-var tc/quick-check tc-ns)})

#_(require '[clojure.test.check.clojure-test :as tct])

#_(def tct-ns (sci/create-ns 'clojure.test.check nil))

#_(doseq [k (sort (keys (ns-publics 'clojure.test.check.clojure-test)))]
  (println (str "'" k) (format "(sci/copy-var tct/%s tct-ns)" k)))

#_(def test-check-clojure-test-namespace
  {'*default-opts* (sci/copy-var tct/*default-opts* tct-ns)
   '*default-test-count* (sci/copy-var tct/*default-test-count* tct-ns)
   '*report-completion* (sci/copy-var tct/*report-completion* tct-ns)
   '*report-shrinking* (sci/copy-var tct/*report-shrinking* tct-ns)
   '*report-trials* (sci/copy-var tct/*report-trials* tct-ns)
   '*trial-report-period* (sci/copy-var tct/*trial-report-period* tct-ns)
   'assert-check (sci/copy-var tct/assert-check tct-ns)
   'default-reporter-fn (sci/copy-var tct/default-reporter-fn tct-ns)
   'defspec (sci/copy-var tct/defspec tct-ns)
   'process-options (sci/copy-var tct/process-options tct-ns)
   'trial-report-dots (sci/copy-var tct/trial-report-dots tct-ns)
   'trial-report-periodic (sci/copy-var tct/trial-report-periodic tct-ns)
   'with-test-out* (sci/copy-var tct/with-test-out* tct-ns)})
