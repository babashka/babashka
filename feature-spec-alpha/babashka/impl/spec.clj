(ns babashka.impl.spec
  {:no-doc true}
  (:require [babashka.impl.clojure.spec.alpha :as s]
            [babashka.impl.clojure.spec.gen.alpha :as gen]
            [babashka.impl.clojure.spec.test.alpha :as test]
            [clojure.core :as c]
            [sci.core :as sci :refer [copy-var]]))

(def sns (sci/create-ns 'clojure.spec.alpha nil))
(def tns (sci/create-ns 'clojure.spec.test.alpha nil))
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
   'map-of (copy-var s/map-of sns)
   'spec (copy-var s/spec sns)
   'spec-impl (copy-var s/spec-impl sns)
   'tuple (copy-var s/tuple sns)
   'tuple-impl (copy-var s/tuple-impl sns)
   'conformer (copy-var s/conformer sns)
   #_#_'explain-data (copy-var s/explain-data sns)})

#_:clj-kondo/ignore
(def test-namespace
  {'instrument (copy-var test/instrument tns)
   'unstrument (copy-var test/unstrument tns)})

#_:clj-kondo/ignore
(def gen-namespace
  {'generate (copy-var gen/generate gns)})

;; def-impl
;; -> spec? ;; OK
;;    regex?
;;    spec-impl
;;    with-name
