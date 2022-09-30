(ns babashka.impl.spec
  {:no-doc true}
  (:require [babashka.impl.clojure.spec.alpha :as s :refer [sns]]
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

;; TODO: fix error in clj-kondo: def is a special form which should always be resolved as the special form
#_:clj-kondo/ignore
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
   'registry (copy-var s/registry sns)})

#_:clj-kondo/ignore
(def test-namespace
  {'instrument (copy-var test/instrument tns)
   'unstrument (copy-var test/unstrument tns)
   '*instrument-enabled* test/instrument-enabled-var
   'with-instrument-disabled (copy-var test/with-instrument-disabled tns)
   'stacktrace-relevant-to-instrument (copy-var test/stacktrace-relevant-to-instrument tns)
   'spec-checking-fn (copy-var test/spec-checking-fn tns)})

#_:clj-kondo/ignore
(def gen-namespace
  {'fmap (copy-var gen/fmap gns)
   'generate (copy-var gen/generate gns)
   'large-integer* (copy-var gen/large-integer* gns)
   'double* (copy-var gen/double* gns)
   'return (copy-var gen/return gns)
   'symbol (copy-var gen/symbol gns)})

;; def-impl
;; -> spec? ;; OK
;;    regex?
;;    spec-impl
;;    with-name
