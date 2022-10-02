(ns babashka.impl.spec
  {:no-doc true}
  (:require
   [babashka.impl.clojure.spec.alpha :as s :refer [sns]]
   [babashka.impl.clojure.spec.gen.alpha :as gen]
   [babashka.impl.clojure.spec.test.alpha :as test :refer [tns]]
   [clojure.core :as c]
   [sci.core :as sci :refer [copy-var]]))

(def gns (sci/create-ns 'clojure.spec.gen.alpha nil))

(defn- ns-qualify
  "Qualify symbol s by resolving it or using the current *ns*."
  [s]
  (if-let [ns-sym (some-> s namespace symbol)]
    (c/or (some-> (get (ns-aliases *ns*) ns-sym) str (symbol (name s)))
          s)
    (symbol (str (.name *ns*)) (str s))))

(c/defn def
  "Given a namespace-qualified keyword or resolvable symbol k, and a
  spec, spec-name, predicate or regex-op makes an entry in the
  registry mapping k to the spec. Use nil to remove an entry in
  the registry for k."
  [_ _ k spec-form]
  (let [k (if (symbol? k) (ns-qualify k) k)]
    `(clojure.spec.alpha/def-impl '~k '~(#'s/res spec-form) ~spec-form)))

(def spec-namespace
  {'def (sci/copy-var s/def sns)
   'def-impl (copy-var s/def-impl sns)
   'valid? (copy-var s/valid? sns)
   'gen (copy-var s/gen sns)
   '* (copy-var s/* sns)
   'rep-impl (copy-var s/rep-impl sns)
   '+ (copy-var s/+ sns)
   'rep+impl (copy-var s/rep+impl sns)
   '? (copy-var s/? sns)
   'maybe-impl (copy-var s/maybe-impl sns)
   '& (copy-var s/& sns)
   'amp-impl (copy-var s/amp-impl sns)
   'and (copy-var s/and sns)
   'and-spec-impl (copy-var s/and-spec-impl sns)
   'or (copy-var s/or sns)
   'or-spec-impl (copy-var s/or-spec-impl sns)
   'cat (copy-var s/cat sns)
   'cat-impl (copy-var s/cat-impl sns)
   'alt (copy-var s/alt sns)
   'alt-impl (copy-var s/alt-impl sns)
   'fdef (copy-var s/fdef sns)
   'fspec (copy-var s/fspec sns)
   'fspec-impl (copy-var s/fspec-impl sns)
   'every (copy-var s/every sns)
   'every-impl (copy-var s/every-impl sns)
   'every-kv (copy-var s/every-kv sns)
   'keys (copy-var s/keys sns)
   'map-spec-impl (copy-var s/map-spec-impl sns)
   'map-of (copy-var s/map-of sns)
   'spec (copy-var s/spec sns)
   'spec-impl (copy-var s/spec-impl sns)
   'tuple (copy-var s/tuple sns)
   'tuple-impl (copy-var s/tuple-impl sns)
   'coll-of (copy-var s/coll-of sns)
   'conformer (copy-var s/conformer sns)
   'int-in (copy-var s/int-in sns)
   'int-in-range? (copy-var s/int-in-range? sns)
   'double-in (copy-var s/double-in sns)
   'inst-in (copy-var s/inst-in sns)
   'inst-in-range? (copy-var s/inst-in-range? sns)
   'conform (copy-var s/conform sns)
   'explain-data (copy-var s/explain-data sns)
   'describe (copy-var s/describe sns)
   'form (copy-var s/form sns)
   'unform (copy-var s/unform sns)
   'nilable (copy-var s/nilable sns)
   'nilable-impl (copy-var s/nilable-impl sns)
   'nonconforming (copy-var s/nonconforming sns)
   'get-spec (copy-var s/get-spec sns)
   'exercise (copy-var s/exercise sns)
   'merge (copy-var s/merge sns)
   'merge-spec-impl (copy-var s/merge-spec-impl sns)
   'keys* (copy-var s/keys* sns)
   'with-gen (copy-var s/with-gen sns)
   'check-asserts (copy-var s/check-asserts sns)
   '*explain-out* s/explain-out-var
   'multi-spec (copy-var s/multi-spec sns)
   'multi-spec-impl (copy-var s/multi-spec-impl sns)
   'spec? (copy-var s/spec? sns)
   'assert (copy-var s/assert sns)
   'assert* (copy-var s/assert* sns)
   'explain-printer (copy-var s/explain-printer sns)
   ;; PRIVATE, but exposed for expound
   'maybe-spec (copy-var s/maybe-spec sns)
   'spec-name (copy-var s/spec-name sns)
   'explain-data* (copy-var s/explain-data* sns)
   '->sym (copy-var s/->sym sns)
   'explain-str (copy-var s/explain-str sns)
   'registry (copy-var s/registry sns)
   'explain-out (copy-var s/explain-out sns)})

#_:clj-kondo/ignore
(def test-namespace
  {'instrument (copy-var test/instrument tns)
   'unstrument (copy-var test/unstrument tns)
   '*instrument-enabled* test/instrument-enabled-var
   'with-instrument-disabled (copy-var test/with-instrument-disabled tns)
   'stacktrace-relevant-to-instrument (copy-var test/stacktrace-relevant-to-instrument tns)
   'spec-checking-fn test/spec-checking-fn-var})

#_(let [syms '[uuid gen-for-pred lazy-prim set one-of any-printable vector-distinct boolean string-alphanumeric map delay simple-type char bind symbol-ns any shuffle lazy-prims cat double char-alpha int return gen-for-name symbol quick-check char-alphanumeric choose for-all* string-ascii frequency double* generate delay-impl lazy-combinators tuple string vector large-integer keyword-ns not-empty elements sample list large-integer* keyword hash-map ratio such-that fmap char-ascii simple-type-printable lazy-combinator bytes]]
  #_:clj-kondo/ignore
  (println
   (zipmap (map #(list 'quote %) syms)
           (map (fn [sym]
                  (list 'copy-var (symbol "gen" (str sym)) 'gns))
                syms))))

#_:clj-kondo/ignore
(def gen-namespace
  {(quote lazy-prim) (copy-var gen/lazy-prim gns), (quote char-alpha) (copy-var gen/char-alpha gns), (quote large-integer*) (copy-var gen/large-integer* gns), (quote bind) (copy-var gen/bind gns), (quote gen-for-pred) (copy-var gen/gen-for-pred gns), (quote lazy-combinator) (copy-var gen/lazy-combinator gns), (quote ratio) (copy-var gen/ratio gns), (quote keyword-ns) (copy-var gen/keyword-ns gns), (quote fmap) (copy-var gen/fmap gns), (quote char-alphanumeric) (copy-var gen/char-alphanumeric gns), (quote int) (copy-var gen/int gns), (quote such-that) (copy-var gen/such-that gns), (quote double*) (copy-var gen/double* gns), (quote quick-check) (copy-var gen/quick-check gns), (quote cat) (copy-var gen/cat gns), (quote one-of) (copy-var gen/one-of gns), (quote choose) (copy-var gen/choose gns), (quote uuid) (copy-var gen/uuid gns), (quote string-ascii) (copy-var gen/string-ascii gns), (quote string) (copy-var gen/string gns), (quote char) (copy-var gen/char gns), (quote tuple) (copy-var gen/tuple gns), (quote elements) (copy-var gen/elements gns), (quote simple-type) (copy-var gen/simple-type gns), (quote frequency) (copy-var gen/frequency gns), (quote symbol-ns) (copy-var gen/symbol-ns gns), (quote for-all*) (copy-var gen/for-all* gns), (quote simple-type-printable) (copy-var gen/simple-type-printable gns), (quote generate) (copy-var gen/generate gns), (quote boolean) (copy-var gen/boolean gns), (quote hash-map) (copy-var gen/hash-map gns), (quote gen-for-name) (copy-var gen/gen-for-name gns), (quote shuffle) (copy-var gen/shuffle gns), (quote delay-impl) (copy-var gen/delay-impl gns), (quote large-integer) (copy-var gen/large-integer gns), (quote map) (copy-var gen/map gns), (quote any) (copy-var gen/any gns), (quote vector) (copy-var gen/vector gns), (quote lazy-combinators) (copy-var gen/lazy-combinators gns), (quote return) (copy-var gen/return gns), (quote keyword) (copy-var gen/keyword gns), (quote list) (copy-var gen/list gns), (quote delay) (copy-var gen/delay gns), (quote vector-distinct) (copy-var gen/vector-distinct gns), (quote symbol) (copy-var gen/symbol gns), (quote lazy-prims) (copy-var gen/lazy-prims gns), (quote bytes) (copy-var gen/bytes gns), (quote double) (copy-var gen/double gns), (quote char-ascii) (copy-var gen/char-ascii gns), (quote string-alphanumeric) (copy-var gen/string-alphanumeric gns), (quote any-printable) (copy-var gen/any-printable gns), (quote not-empty) (copy-var gen/not-empty gns), (quote sample) (copy-var gen/sample gns), (quote set) (copy-var gen/set gns)})

;; def-impl
;; -> spec? ;; OK
;;    regex?
;;    spec-impl
;;    with-name
