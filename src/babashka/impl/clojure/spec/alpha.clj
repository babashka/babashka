;;   Copyright (c) Rich Hickey. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns
    ^{:doc "The spec library specifies the structure of data or functions and provides
  operations to validate, conform, explain, describe, and generate data based on
  the specs.

  Rationale: https://clojure.org/about/spec
  Guide: https://clojure.org/guides/spec"}
    babashka.impl.clojure.spec.alpha
  (:refer-clojure :exclude [+ * and assert or cat def keys merge])
  (:require
   [babashka.impl.clojure.spec.gen.alpha :as gen]
   [babashka.impl.common :refer [ctx]]
   [clojure.walk :as walk]
   [sci.core :as sci]
   [sci.lang]))

(alias 'c 'clojure.core)

(set! *warn-on-reflection* true)

(def ^:dynamic *recursion-limit*
  "A soft limit on how many times a branching spec (or/alt/*/opt-keys/multi-spec)
  can be recursed through during generation. After this a
  non-recursive branch will be chosen."
  4)

(def ^:dynamic *fspec-iterations*
  "The number of times an anonymous fn specified by fspec will be (generatively) tested during conform"
  21)

(def ^:dynamic *coll-check-limit*
  "The number of elements validated in a collection spec'ed with 'every'"
  101)

(def ^:dynamic *coll-error-limit*
  "The number of errors reported by explain in a collection spec'ed with 'every'"
  20)

(defprotocol Spec
  (conform* [spec x])
  (unform* [spec y])
  (explain* [spec path via in x])
  (gen* [spec overrides path rmap])
  (with-gen* [spec gfn])
  (describe* [spec]))

(defonce ^:private registry-ref (atom {}))

(defn- deep-resolve [reg k]
  (loop [spec k]
    (if (ident? spec)
      (recur (get reg spec))
      spec)))

(defn- reg-resolve
  "returns the spec/regex at end of alias chain starting with k, nil if not found, k if k not ident"
  [k]
  (if (ident? k)
    (let [reg @registry-ref
          spec (get reg k)]
      (if-not (ident? spec)
        spec
        (deep-resolve reg spec)))
    k))

(defn- reg-resolve!
  "returns the spec/regex at end of alias chain starting with k, throws if not found, k if k not ident"
  [k]
  (if (ident? k)
    (c/or (reg-resolve k)
          (throw (Exception. (str "Unable to resolve spec: " k))))
    k))

(defn spec?
  "returns x if x is a spec object, else logical false"
  [x]
  (when (instance? babashka.impl.clojure.spec.alpha.Spec x)
    x))

(defn regex?
  "returns x if x is a (clojure.spec) regex op, else logical false"
  [x]
  (c/and (:clojure.spec.alpha/op x) x))

(defn- with-name [spec name]
  (cond
    (ident? spec) spec
    (regex? spec) (assoc spec :clojure.spec.alpha/name name)

    (instance? clojure.lang.IObj spec)
    (with-meta spec (assoc (meta spec) :clojure.spec.alpha/name name))))

(defn spec-name [spec]
  (cond
    (ident? spec) spec

    (regex? spec) (:clojure.spec.alpha/name spec)

    (instance? clojure.lang.IObj spec)
    (-> (meta spec) :clojure.spec.alpha/name)))

(declare spec-impl)
(declare regex-spec-impl)

(defn maybe-spec
  "spec-or-k must be a spec, regex or resolvable kw/sym, else returns nil."
  [spec-or-k]
  (let [s (c/or (c/and (ident? spec-or-k) (reg-resolve spec-or-k))
                (spec? spec-or-k)
                (regex? spec-or-k)
                nil)]
    (if (regex? s)
      (with-name (regex-spec-impl s nil) (spec-name s))
      s)))

(defn- the-spec
  "spec-or-k must be a spec, regex or kw/sym, else returns nil. Throws if unresolvable kw/sym"
  [spec-or-k]
  (c/or (maybe-spec spec-or-k)
        (when (ident? spec-or-k)
          (throw (Exception. (str "Unable to resolve spec: " spec-or-k))))))

(defprotocol Specize
  (specize* [_] [_ form]))

(defn- fn-sym [^Object f]
  (let [[_ f-ns f-n] (re-matches #"(.*)\$(.*?)(__[0-9]+)?" (.. f getClass getName))]
    ;; check for anonymous function
    (when (not= "fn" f-n)
      (symbol (clojure.lang.Compiler/demunge f-ns) (clojure.lang.Compiler/demunge f-n)))))

(extend-protocol Specize
  clojure.lang.Keyword
  (specize* ([k] (specize* (reg-resolve! k)))
    ([k _] (specize* (reg-resolve! k))))

  clojure.lang.Symbol
  (specize* ([s] (specize* (reg-resolve! s)))
    ([s _] (specize* (reg-resolve! s))))

  clojure.lang.IPersistentSet
  (specize* ([s] (spec-impl s s nil nil))
    ([s form] (spec-impl form s nil nil)))

  Object
  (specize* ([o] (if (c/and (not (map? o)) (ifn? o))
                   (if-let [s (fn-sym o)]
                     (spec-impl s o nil nil)
                     (spec-impl :clojure.spec.alpha/unknown o nil nil))
                   (spec-impl :clojure.spec.alpha/unknown o nil nil)))
    ([o form] (spec-impl form o nil nil))))

(defn- specize
  ([s] (c/or (spec? s) (specize* s)))
  ([s form] (c/or (spec? s) (specize* s form))))

(defn invalid?
  "tests the validity of a conform return value"
  [ret]
  (identical? :clojure.spec.alpha/invalid ret))

(defn conform
  "Given a spec and a value, returns :clojure.spec.alpha/invalid
        if value does not match spec, else the (possibly destructured) value."
  [spec x]
  (conform* (specize spec) x))

(defn unform
  "Given a spec and a value created by or compliant with a call to
  'conform' with the same spec, returns a value with all conform
  destructuring undone."
  [spec x]
  (unform* (specize spec) x))

(defn form
  "returns the spec as data"
  [spec]
  ;;TODO - incorporate gens
  (describe* (specize spec)))

(defn abbrev [form]
  (cond
    (seq? form)
    (walk/postwalk (fn [form]
                     (cond
                       (c/and (symbol? form) (namespace form))
                       (-> form name symbol)

                       (c/and (seq? form) (= 'fn (first form)) (= '[%] (second form)))
                       (last form)

                       :else form))
                   form)

    (c/and (symbol? form) (namespace form))
    (-> form name symbol)

    :else form))

(defn describe
  "returns an abbreviated description of the spec as data"
  [spec]
  (abbrev (form spec)))

(defn with-gen
  "Takes a spec and a no-arg, generator-returning fn and returns a version of that spec that uses that generator"
  [spec gen-fn]
  (let [spec (reg-resolve spec)]
    (if (regex? spec)
      (assoc spec :clojure.spec.alpha/gfn gen-fn)
      (with-gen* (specize spec) gen-fn))))

(defn explain-data* [spec path via in x]
  (let [probs (explain* (specize spec) path via in x)]
    (when-not (empty? probs)
      {:clojure.spec.alpha/problems probs
       :clojure.spec.alpha/spec spec
       :clojure.spec.alpha/value x})))

(defn explain-data
  "Given a spec and a value x which ought to conform, returns nil if x
  conforms, else a map with at least the key :clojure.spec.alpha/problems whose value is
  a collection of problem-maps, where problem-map has at least :path :pred and :val
  keys describing the predicate and the value that failed at that
  path."
  [spec x]
  (explain-data* spec [] (if-let [name (spec-name spec)] [name] []) [] x))

(defn explain-printer
  "Default printer for explain-data. nil indicates a successful validation."
  [ed]
  (binding [*out* @sci/out]
    (if ed
      (let [problems (->> (:clojure.spec.alpha/problems ed)
                          (sort-by #(- (count (:in %))))
                          (sort-by #(- (count (:path %)))))]
        ;;(prn {:ed ed})
        (doseq [{:keys [path pred val reason via in] :as prob} problems]
          (pr val)
          (print " - failed: ")
          (if reason (print reason) (pr (abbrev pred)))
          (when-not (empty? in)
            (print (str " in: " (pr-str in))))
          (when-not (empty? path)
            (print (str " at: " (pr-str path))))
          (when-not (empty? via)
            (print (str " spec: " (pr-str (last via)))))
          (doseq [[k v] prob]
            (when-not (#{:path :pred :val :reason :via :in} k)
              (print "\n\t" (pr-str k) " ")
              (pr v)))
          (newline)))
      (println "Success!"))))

(def sns (sci/create-ns 'clojure.spec.alpha nil))

(def explain-out-var (sci/new-dynamic-var '*explain-out* explain-printer {:ns sns}))

(defn explain-out
  "Prints explanation data (per 'explain-data') to *out* using the printer in *explain-out*,
   by default explain-printer."
  [ed]
  (@explain-out-var ed))

(defn explain
  "Given a spec and a value that fails to conform, prints an explanation to *out*."
  [spec x]
  (explain-out (explain-data spec x)))

(defn explain-str
  "Given a spec and a value that fails to conform, returns an explanation as a string."
  [spec x]
  (sci/with-out-str (explain spec x)))

(declare valid?)

(defn- gensub
  [spec overrides path rmap form]
  ;;(prn {:spec spec :over overrides :path path :form form})
  (let [spec (specize spec)]
    (if-let [g (c/or (when-let [gfn (c/or (get overrides (c/or (spec-name spec) spec))
                                          (get overrides path))]
                       (gfn))
                     (gen* spec overrides path rmap))]
      (gen/such-that #(valid? spec %) g 100)
      (let [abbr (abbrev form)]
        (throw (ex-info (str "Unable to construct gen at: " path " for: " abbr)
                        {:clojure.spec.alpha/path path :clojure.spec.alpha/form form :clojure.spec.alpha/failure :no-gen}))))))

(defn gen
  "Given a spec, returns the generator for it, or throws if none can
  be constructed. Optionally an overrides map can be provided which
  should map spec names or paths (vectors of keywords) to no-arg
  generator-creating fns. These will be used instead of the generators at those
  names/paths. Note that parent generator (in the spec or overrides
  map) will supersede those of any subtrees. A generator for a regex
  op must always return a sequential collection (i.e. a generator for
  s/? should return either an empty sequence/vector or a
  sequence/vector with one item in it)"
  ([spec] (gen spec nil))
  ([spec overrides] (gensub spec overrides [] {:clojure.spec.alpha/recursion-limit *recursion-limit*} spec)))

(defn ->sym
  "Returns a symbol from a symbol or var"
  [x]
  (if (instance? sci.lang.Var x)
    (sci/var->symbol x)
    x))

(defn- unfn [expr]
  (if (c/and (seq? expr)
             (symbol? (first expr))
             (= "fn*" (name (first expr))))
    (let [[[s] & form] (rest expr)]
      (conj (walk/postwalk-replace {s '%} form) '[%] 'fn))
    expr))

(defn sci-resolve [sym]
  (sci/resolve @ctx sym))

(defn res [form]
  (cond
    (keyword? form) form
    (symbol? form) (c/or
                    (-> form sci-resolve ->sym) form)
    (sequential? form) (walk/postwalk #(if (symbol? %) (res %) %) (unfn form))
    :else form))

(defn ^:skip-wiki def-impl
  "Do not call this directly, use 'def'"
  [k form spec]
  (c/assert (c/and (ident? k) (namespace k)) "k must be namespaced keyword or resolvable symbol")
  (if (nil? spec)
    (swap! registry-ref dissoc k)
    (let [spec (if (c/or (spec? spec) (regex? spec) (get @registry-ref spec))
                 spec
                 (spec-impl form spec nil nil))]
      (swap! registry-ref assoc k (with-name spec k))))
  k)

(defn sci-ns-aliases []
  (sci/eval-form @ctx (list 'ns-aliases @sci/ns)))

(defn ns-qualify
  "Qualify symbol s by resolving it or using the current *ns*."
  [s]
  (if-let [ns-sym (some-> s namespace symbol)]
    (c/or (some->
           (get (sci-ns-aliases) ns-sym) str (symbol (name s)))
          s)
    (symbol (str @sci/ns) (str s))))

(defmacro def
  "Given a namespace-qualified keyword or resolvable symbol k, and a
  spec, spec-name, predicate or regex-op makes an entry in the
  registry mapping k to the spec. Use nil to remove an entry in
  the registry for k."
  [k spec-form]
  (let [k (if (symbol? k) (ns-qualify k) k)]
    `(clojure.spec.alpha/def-impl '~k '~(res spec-form) ~spec-form)))

(defmacro internal-def
  [k spec-form]
  `(def-impl '~k '~(res spec-form) ~spec-form))

(defn registry
  "returns the registry map, prefer 'get-spec' to lookup a spec by name"
  []
  @registry-ref)

(defn get-spec
  "Returns spec registered for keyword/symbol/var k, or nil."
  [k]
  (get (registry) (if (keyword? k) k (->sym k))))

(defmacro spec
  "Takes a single predicate form, e.g. can be the name of a predicate,
  like even?, or a fn literal like #(< % 42). Note that it is not
  generally necessary to wrap predicates in spec when using the rest
  of the spec macros, only to attach a unique generator

  Can also be passed the result of one of the regex ops -
  cat, alt, *, +, ?, in which case it will return a regex-conforming
  spec, useful when nesting an independent regex.
  ---

  Optionally takes :gen generator-fn, which must be a fn of no args that
  returns a test.check generator.

  Returns a spec."
  [form & {:keys [gen]}]
  (when form
    `(clojure.spec.alpha/spec-impl '~(res form) ~form ~gen nil)))

(defmacro multi-spec
  "Takes the name of a spec/predicate-returning multimethod and a
  tag-restoring keyword or fn (retag).  Returns a spec that when
  conforming or explaining data will pass it to the multimethod to get
  an appropriate spec. You can e.g. use multi-spec to dynamically and
  extensibly associate specs with 'tagged' data (i.e. data where one
  of the fields indicates the shape of the rest of the structure).

  (defmulti mspec :tag)

  The methods should ignore their argument and return a predicate/spec:
  (defmethod mspec :int [_] (s/keys :req-un [:clojure.spec.alpha/tag :clojure.spec.alpha/i]))

  retag is used during generation to retag generated values with
  matching tags. retag can either be a keyword, at which key the
  dispatch-tag will be assoc'ed, or a fn of generated value and
  dispatch-tag that should return an appropriately retagged value.

  Note that because the tags themselves comprise an open set,
  the tag key spec cannot enumerate the values, but can e.g.
  test for keyword?.

  Note also that the dispatch values of the multimethod will be
  included in the path, i.e. in reporting and gen overrides, even
  though those values are not evident in the spec.
  "
  [mm retag]
  `(clojure.spec.alpha/multi-spec-impl '~(res mm) (var ~mm) ~retag))

(defmacro keys
  "Creates and returns a map validating spec. :req and :opt are both
  vectors of namespaced-qualified keywords. The validator will ensure
  the :req keys are present. The :opt keys serve as documentation and
  may be used by the generator.

  The :req key vector supports 'and' and 'or' for key groups:

  (s/keys :req [:clojure.spec.alpha/x :clojure.spec.alpha/y (or :clojure.spec.alpha/secret (and :clojure.spec.alpha/user :clojure.spec.alpha/pwd))] :opt [:clojure.spec.alpha/z])

  There are also -un versions of :req and :opt. These allow
  you to connect unqualified keys to specs.  In each case, fully
  qualfied keywords are passed, which name the specs, but unqualified
  keys (with the same name component) are expected and checked at
  conform-time, and generated during gen:

  (s/keys :req-un [:my.ns/x :my.ns/y])

  The above says keys :x and :y are required, and will be validated
  and generated by specs (if they exist) named :my.ns/x :my.ns/y
  respectively.

  In addition, the values of *all* namespace-qualified keys will be validated
  (and possibly destructured) by any registered specs. Note: there is
  no support for inline value specification, by design.

  Optionally takes :gen generator-fn, which must be a fn of no args that
  returns a test.check generator."
  [& {:keys [req req-un opt opt-un gen]}]
  (let [unk #(-> % name keyword)
        req-keys (filterv keyword? (flatten req))
        req-un-specs (filterv keyword? (flatten req-un))
        _ (c/assert (every? #(c/and (keyword? %) (namespace %)) (concat req-keys req-un-specs opt opt-un))
                    "all keys must be namespace-qualified keywords")
        req-specs (into req-keys req-un-specs)
        req-keys (into req-keys (map unk req-un-specs))
        opt-keys (into (vec opt) (map unk opt-un))
        opt-specs (into (vec opt) opt-un)
        gx (gensym)
        parse-req (fn [rk f]
                    (map (fn [x]
                           (if (keyword? x)
                             `(contains? ~gx ~(f x))
                             (walk/postwalk
                              (fn [y] (if (keyword? y) `(contains? ~gx ~(f y)) y))
                              x)))
                         rk))
        pred-exprs [`(map? ~gx)]
        pred-exprs (into pred-exprs (parse-req req identity))
        pred-exprs (into pred-exprs (parse-req req-un unk))
        keys-pred `(fn* [~gx] (c/and ~@pred-exprs))
        pred-exprs (mapv (fn [e] `(fn* [~gx] ~e)) pred-exprs)
        pred-forms (walk/postwalk res pred-exprs)]
    ;; `(map-spec-impl ~req-keys '~req ~opt '~pred-forms ~pred-exprs ~gen)
    `(clojure.spec.alpha/map-spec-impl {:req '~req :opt '~opt :req-un '~req-un :opt-un '~opt-un
                     :req-keys '~req-keys :req-specs '~req-specs
                     :opt-keys '~opt-keys :opt-specs '~opt-specs
                     :pred-forms '~pred-forms
                     :pred-exprs ~pred-exprs
                     :keys-pred ~keys-pred
                     :gfn ~gen})))

(defmacro or
  "Takes key+pred pairs, e.g.

  (s/or :even even? :small #(< % 42))

  Returns a destructuring spec that returns a map entry containing the
  key of the first matching pred and the corresponding value. Thus the
  'key' and 'val' functions can be used to refer generically to the
  components of the tagged return."
  [& key-pred-forms]
  (let [pairs (partition 2 key-pred-forms)
        keys (mapv first pairs)
        pred-forms (mapv second pairs)
        pf (mapv res pred-forms)]
    (c/assert (c/and (even? (count key-pred-forms)) (every? keyword? keys)) "spec/or expects k1 p1 k2 p2..., where ks are keywords")
    `(clojure.spec.alpha/or-spec-impl ~keys '~pf ~pred-forms nil)))

(defmacro and
  "Takes predicate/spec-forms, e.g.

  (s/and even? #(< % 42))

  Returns a spec that returns the conformed value. Successive
  conformed values propagate through rest of predicates."
  [& pred-forms]
  `(clojure.spec.alpha/and-spec-impl '~(mapv res pred-forms) ~(vec pred-forms) nil))

(defmacro merge
  "Takes map-validating specs (e.g. 'keys' specs) and
  returns a spec that returns a conformed map satisfying all of the
  specs.  Unlike 'and', merge can generate maps satisfying the
  union of the predicates."
  [& pred-forms]
  `(clojure.spec.alpha/merge-spec-impl '~(mapv res pred-forms) ~(vec pred-forms) nil))

(defn- res-kind
  [opts]
  (let [{kind :kind :as mopts} opts]
    (->>
     (if kind
       (assoc mopts :kind `~(res kind))
       mopts)
     (mapcat identity))))

(defmacro every
  "takes a pred and validates collection elements against that pred.

  Note that 'every' does not do exhaustive checking, rather it samples
  *coll-check-limit* elements. Nor (as a result) does it do any
  conforming of elements. 'explain' will report at most *coll-error-limit*
  problems.  Thus 'every' should be suitable for potentially large
  collections.

  Takes several kwargs options that further constrain the collection:

  :kind - a pred that the collection type must satisfy, e.g. vector?
        (default nil) Note that if :kind is specified and :into is
        not, this pred must generate in order for every to generate.
  :count - specifies coll has exactly this count (default nil)
  :min-count, :max-count - coll has count (<= min-count count max-count) (defaults nil)
  :distinct - all the elements are distinct (default nil)

  And additional args that control gen

  :gen-max - the maximum coll size to generate (default 20)
  :into - one of [], (), {}, #{} - the default collection to generate into
      (default: empty coll as generated by :kind pred if supplied, else [])

  Optionally takes :gen generator-fn, which must be a fn of no args that
  returns a test.check generator

  See also - coll-of, every-kv
"
  [pred & {:keys [into kind count max-count min-count distinct gen-max gen] :as opts}]
  (let [desc (:clojure.spec.alpha/describe opts)
        nopts (-> opts
                  (dissoc :gen :clojure.spec.alpha/describe)
                  (assoc :clojure.spec.alpha/kind-form `'~(res (:kind opts))
                         :clojure.spec.alpha/describe (c/or desc `'(clojure.spec.alpha/every ~(res pred) ~@(res-kind opts)))))
        gx (gensym)
        cpreds (cond-> [(list (c/or kind `coll?) gx)]
                 count (conj `(= ~count (bounded-count ~count ~gx)))

                 (c/or min-count max-count)
                 (conj `(<= (c/or ~min-count 0)
                            (bounded-count (if ~max-count (inc ~max-count) ~min-count) ~gx)
                            (c/or ~max-count Integer/MAX_VALUE)))

                 distinct
                 (conj `(c/or (empty? ~gx) (apply distinct? ~gx))))]
    `(clojure.spec.alpha/every-impl '~pred ~pred ~(assoc nopts :clojure.spec.alpha/cpred `(fn* [~gx] (c/and ~@cpreds))) ~gen)))

(defmacro every-kv
  "like 'every' but takes separate key and val preds and works on associative collections.

  Same options as 'every', :into defaults to {}

  See also - map-of"

  [kpred vpred & opts]
  (let [desc `(clojure.spec.alpha/every-kv ~(res kpred) ~(res vpred) ~@(res-kind opts))]
    `(clojure.spec.alpha/every (clojure.spec.alpha/tuple ~kpred ~vpred) :clojure.spec.alpha/kfn (fn [i# v#] (nth v# 0)) :into {} :clojure.spec.alpha/describe '~desc ~@opts)))

(defmacro coll-of
  "Returns a spec for a collection of items satisfying pred. Unlike
  'every', coll-of will exhaustively conform every value.

  Same options as 'every'. conform will produce a collection
  corresponding to :into if supplied, else will match the input collection,
  avoiding rebuilding when possible.

  See also - every, map-of"
  [pred & opts]
  (let [desc `(clojure.spec.alpha/coll-of ~(res pred) ~@(res-kind opts))]
    `(clojure.spec.alpha/every ~pred :clojure.spec.alpha/conform-all true :clojure.spec.alpha/describe '~desc ~@opts)))

(defmacro map-of
  "Returns a spec for a map whose keys satisfy kpred and vals satisfy
  vpred. Unlike 'every-kv', map-of will exhaustively conform every
  value.

  Same options as 'every', :kind defaults to map?, with the addition of:

  :conform-keys - conform keys as well as values (default false)

  See also - every-kv"
  [kpred vpred & opts]
  (let [desc `(clojure.spec.alpha/map-of ~(res kpred) ~(res vpred) ~@(res-kind opts))]
    `(clojure.spec.alpha/every-kv ~kpred ~vpred :clojure.spec.alpha/conform-all true :kind map? :clojure.spec.alpha/describe '~desc ~@opts)))


(defmacro *
  "Returns a regex op that matches zero or more values matching
  pred. Produces a vector of matches iff there is at least one match"
  [pred-form]
  `(clojure.spec.alpha/rep-impl '~(res pred-form) ~pred-form))

(defmacro +
  "Returns a regex op that matches one or more values matching
  pred. Produces a vector of matches"
  [pred-form]
  `(clojure.spec.alpha/rep+impl '~(res pred-form) ~pred-form))

(defmacro ?
  "Returns a regex op that matches zero or one value matching
  pred. Produces a single value (not a collection) if matched."
  [pred-form]
  `(clojure.spec.alpha/maybe-impl ~pred-form '~(res pred-form)))

(defmacro alt
  "Takes key+pred pairs, e.g.

  (s/alt :even even? :small #(< % 42))

  Returns a regex op that returns a map entry containing the key of the
  first matching pred and the corresponding value. Thus the
  'key' and 'val' functions can be used to refer generically to the
  components of the tagged return"
  [& key-pred-forms]
  (let [pairs (partition 2 key-pred-forms)
        keys (mapv first pairs)
        pred-forms (mapv second pairs)
        pf (mapv res pred-forms)]
    (c/assert (c/and (even? (count key-pred-forms)) (every? keyword? keys)) "alt expects k1 p1 k2 p2..., where ks are keywords")
    `(clojure.spec.alpha/alt-impl ~keys ~pred-forms '~pf)))

(defmacro cat
  "Takes key+pred pairs, e.g.

  (s/cat :e even? :o odd?)

  Returns a regex op that matches (all) values in sequence, returning a map
  containing the keys of each pred and the corresponding value."
  [& key-pred-forms]
  (let [pairs (partition 2 key-pred-forms)
        keys (mapv first pairs)
        pred-forms (mapv second pairs)
        pf (mapv res pred-forms)]
    (c/assert (c/and (even? (count key-pred-forms)) (every? keyword? keys)) "cat expects k1 p1 k2 p2..., where ks are keywords")
    `(clojure.spec.alpha/cat-impl ~keys ~pred-forms '~pf)))

(defmacro &
  "takes a regex op re, and predicates. Returns a regex-op that consumes
  input as per re but subjects the resulting value to the
  conjunction of the predicates, and any conforming they might perform."
  [re & preds]
  (let [pv (vec preds)]
    `(clojure.spec.alpha/amp-impl ~re '~(res re) ~pv '~(mapv res pv))))

(defmacro conformer
  "takes a predicate function with the semantics of conform i.e. it should return either a
  (possibly converted) value or :clojure.spec.alpha/invalid, and returns a
  spec that uses it as a predicate/conformer. Optionally takes a
  second fn that does unform of result of first"
  ([f] `(clojure.spec.alpha/spec-impl '(conformer ~(res f)) ~f nil true))
  ([f unf] `(clojure.spec.alpha/spec-impl '(conformer ~(res f) ~(res unf)) ~f nil true ~unf)))

(defmacro internal-conformer
  "takes a predicate function with the semantics of conform i.e. it should return either a
  (possibly converted) value or :clojure.spec.alpha/invalid, and returns a
  spec that uses it as a predicate/conformer. Optionally takes a
  second fn that does unform of result of first"
  ([f] `(babashka.impl.clojure.spec.alpha/spec-impl '(conformer ~(res f)) ~f nil true))
  ([f unf] `(babashka.impl.clojure.spec.alpha/spec-impl '(conformer ~(res f) ~(res unf)) ~f nil true ~unf)))

(defmacro fspec
  "takes :args :ret and (optional) :fn kwargs whose values are preds
  and returns a spec whose conform/explain take a fn and validates it
  using generative testing. The conformed value is always the fn itself.

  See 'fdef' for a single operation that creates an fspec and
  registers it, as well as a full description of :args, :ret and :fn

  fspecs can generate functions that validate the arguments and
  fabricate a return value compliant with the :ret spec, ignoring
  the :fn spec if present.

  Optionally takes :gen generator-fn, which must be a fn of no args
  that returns a test.check generator."

  [& {:keys [args ret fn gen] :or {ret `any?}}]
  `(clojure.spec.alpha/fspec-impl
    (clojure.spec.alpha/spec ~args) '~(res args)
    (clojure.spec.alpha/spec ~ret) '~(res ret)
    (clojure.spec.alpha/spec ~fn) '~(res fn) ~gen))

(defmacro tuple
  "takes one or more preds and returns a spec for a tuple, a vector
  where each element conforms to the corresponding pred. Each element
  will be referred to in paths using its ordinal."
  [& preds]
  (c/assert (not (empty? preds)))
  `(clojure.spec.alpha/tuple-impl '~(mapv res preds) ~(vec preds)))

(defn- macroexpand-check
  [v args]
  (let [fn-spec (get-spec v)]
    (when-let [arg-spec (:args fn-spec)]
      (when (invalid? (conform arg-spec args))
        (let [ed (assoc (explain-data* arg-spec []
                                       (if-let [name (spec-name arg-spec)] [name] []) [] args)
                        :clojure.spec.alpha/args args)]
          (throw (ex-info
                  (str "Call to " (->sym v) " did not conform to spec.")
                  ed)))))))

(defmacro fdef
  "Takes a symbol naming a function, and one or more of the following:

  :args A regex spec for the function arguments as they were a list to be
    passed to apply - in this way, a single spec can handle functions with
    multiple arities
  :ret A spec for the function's return value
  :fn A spec of the relationship between args and ret - the
    value passed is {:args conformed-args :ret conformed-ret} and is
    expected to contain predicates that relate those values

  Qualifies fn-sym with resolve, or using *ns* if no resolution found.
  Registers an fspec in the global registry, where it can be retrieved
  by calling get-spec with the var or fully-qualified symbol.

  Once registered, function specs are included in doc, checked by
  instrument, tested by the runner clojure.spec.test.alpha/check, and (if
  a macro) used to explain errors during macroexpansion.

  Note that :fn specs require the presence of :args and :ret specs to
  conform values, and so :fn specs will be ignored if :args or :ret
  are missing.

  Returns the qualified fn-sym.

  For example, to register function specs for the symbol function:

  (s/fdef clojure.core/symbol
    :args (s/alt :separate (s/cat :ns string? :n string?)
                 :str string?
                 :sym symbol?)
    :ret symbol?)"
  [fn-sym & specs]
  `(clojure.spec.alpha/def ~fn-sym (clojure.spec.alpha/fspec ~@specs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; impl ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- recur-limit? [rmap id path k]
  (c/and (> (get rmap id) (:clojure.spec.alpha/recursion-limit rmap))
         (contains? (set path) k)))

(defn- inck [m k]
  (assoc m k (inc (c/or (get m k) 0))))

(defn- dt
  ([pred x form] (dt pred x form nil))
  ([pred x form cpred?]
   (if pred
     (if-let [spec (the-spec pred)]
       (conform spec x)
       (if (ifn? pred)
         (if cpred?
           (pred x)
           (if (pred x) x :clojure.spec.alpha/invalid))
         (throw (Exception. (str (pr-str form) " is not a fn, expected predicate fn")))))
     x)))

(defn valid?
  "Helper function that returns true when x is valid for spec."
  ([spec x]
   (let [spec (specize spec)]
     (not (invalid? (conform* spec x)))))
  ([spec x form]
   (let [spec (specize spec form)]
     (not (invalid? (conform* spec x))))))

(defn- pvalid?
  "internal helper function that returns true when x is valid for spec."
  ([pred x]
   (not (invalid? (dt pred x :clojure.spec.alpha/unknown))))
  ([pred x form]
   (not (invalid? (dt pred x form)))))

(defn- explain-1 [form pred path via in v]
  ;;(prn {:form form :pred pred :path path :in in :v v})
  (let [pred (maybe-spec pred)]
    (if (spec? pred)
      (explain* pred path (if-let [name (spec-name pred)] (conj via name) via) in v)
      [{:path path :pred form :val v :via via :in in}])))

(declare or-k-gen and-k-gen)

(defn- k-gen
  "returns a generator for form f, which can be a keyword or a list
  starting with 'or or 'and."
  [f]
  (cond
    (keyword? f)       (gen/return f)
    (= 'or  (first f)) (or-k-gen 1 (rest f))
    (= 'and (first f)) (and-k-gen (rest f))))

(defn- or-k-gen
  "returns a tuple generator made up of generators for a random subset
  of min-count (default 0) to all elements in s."
  ([s] (or-k-gen 0 s))
  ([min-count s]
   (gen/bind (gen/tuple
              (gen/choose min-count (count s))
              (gen/shuffle (map k-gen s)))
             (fn [[n gens]]
               (apply gen/tuple (take n gens))))))

(defn- and-k-gen
  "returns a tuple generator made up of generators for every element
  in s."
  [s]
  (apply gen/tuple (map k-gen s)))


(defn ^:skip-wiki map-spec-impl
  "Do not call this directly, use 'spec' with a map argument"
  [{:keys [req-un opt-un keys-pred pred-exprs opt-keys req-specs req req-keys opt-specs pred-forms opt gfn]
    :as argm}]
  (let [k->s (zipmap (concat req-keys opt-keys) (concat req-specs opt-specs))
        keys->specnames #(c/or (k->s %) %)
        id (java.util.UUID/randomUUID)]
    (reify
      Specize
      (specize* [s] s)
      (specize* [s _] s)

      Spec
      (conform* [_ m]
        (if (keys-pred m)
          (let [reg (registry)]
            (loop [ret m, [[k v] & ks :as keys] m]
              (if keys
                (let [sname (keys->specnames k)]
                  (if-let [s (get reg sname)]
                    (let [cv (conform s v)]
                      (if (invalid? cv)
                        :clojure.spec.alpha/invalid
                        (recur (if (identical? cv v) ret (assoc ret k cv))
                               ks)))
                    (recur ret ks)))
                ret)))
          :clojure.spec.alpha/invalid))
      (unform* [_ m]
        (let [reg (registry)]
          (loop [ret m, [k & ks :as keys] (c/keys m)]
            (if keys
              (if (contains? reg (keys->specnames k))
                (let [cv (get m k)
                      v (unform (keys->specnames k) cv)]
                  (recur (if (identical? cv v) ret (assoc ret k v))
                         ks))
                (recur ret ks))
              ret))))
      (explain* [_ path via in x]
        (if-not (map? x)
          [{:path path :pred `map? :val x :via via :in in}]
          (let [reg (registry)]
            (apply concat
                   (when-let [probs (->> (map (fn [pred form] (when-not (pred x) form))
                                              pred-exprs pred-forms)
                                         (keep identity)
                                         seq)]
                     (map
                      #(identity {:path path :pred % :val x :via via :in in})
                      probs))
                   (map (fn [[k v]]
                          (when-not (c/or (not (contains? reg (keys->specnames k)))
                                          (pvalid? (keys->specnames k) v k))
                            (explain-1 (keys->specnames k) (keys->specnames k) (conj path k) via (conj in k) v)))
                        (seq x))))))
      (gen* [_ overrides path rmap]
        (if gfn
          (gfn)
          (let [rmap (inck rmap id)
                rgen (fn [k s] [k (gensub s overrides (conj path k) rmap k)])
                ogen (fn [k s]
                       (when-not (recur-limit? rmap id path k)
                         [k (gen/delay-internal (gensub s overrides (conj path k) rmap k))]))
                reqs (map rgen req-keys req-specs)
                opts (remove nil? (map ogen opt-keys opt-specs))]
            (when (every? identity (concat (map second reqs) (map second opts)))
              (gen/bind
               (gen/tuple
                (and-k-gen req)
                (or-k-gen opt)
                (and-k-gen req-un)
                (or-k-gen opt-un))
               (fn [[req-ks opt-ks req-un-ks opt-un-ks]]
                 (let [qks (flatten (concat req-ks opt-ks))
                       unqks (map (comp keyword name) (flatten (concat req-un-ks opt-un-ks)))]
                   (->> (into reqs opts)
                        (filter #((set (concat qks unqks)) (first %)))
                        (apply concat)
                        (apply gen/hash-map)))))))))
      (with-gen* [_ gfn] (map-spec-impl (assoc argm :gfn gfn)))
      (describe* [_] (cons 'clojure.spec.alpha/keys
                           (cond-> []
                             req (conj :req req)
                             opt (conj :opt opt)
                             req-un (conj :req-un req-un)
                             opt-un (conj :opt-un opt-un)))))))




(defn ^:skip-wiki spec-impl
  "Do not call this directly, use 'spec'"
  ([form pred gfn cpred?] (spec-impl form pred gfn cpred? nil))
  ([form pred gfn cpred? unc]
   (cond
     (spec? pred) (cond-> pred gfn (with-gen gfn))
     (regex? pred) (regex-spec-impl pred gfn)
     (ident? pred) (cond-> (the-spec pred) gfn (with-gen gfn))
     :else
     (reify
      Specize
      (specize* [s] s)
      (specize* [s _] s)

      Spec
       (conform* [_ x] (let [ret (pred x)]
                        (if cpred?
                          ret
                          (if ret x :clojure.spec.alpha/invalid))))
      (unform* [_ x] (if cpred?
                       (if unc
                         (unc x)
                         (throw (IllegalStateException. "no unform fn for conformer")))
                       x))
      (explain* [_ path via in x]
                (when (invalid? (dt pred x form cpred?))
                  [{:path path :pred form :val x :via via :in in}]))
      (gen* [_ _ _ _] (if gfn
                        (gfn)
                        (gen/gen-for-pred pred)))
      (with-gen* [_ gfn] (spec-impl form pred gfn cpred? unc))
       (describe* [_] form)))))

(def invalid :clojure.spec.alpha/invalid)

(defn ^:skip-wiki multi-spec-impl
  "Do not call this directly, use 'multi-spec'"
  ([form mmvar retag] (multi-spec-impl form mmvar retag nil))
  ([form mmvar retag gfn]
   (let [id (java.util.UUID/randomUUID)
         predx #(let [^clojure.lang.MultiFn mm @mmvar]
                  (c/and (.getMethod mm ((.dispatchFn mm) %))
                         (mm %)))
         dval #((.dispatchFn ^clojure.lang.MultiFn @mmvar) %)
         tag (if (keyword? retag)
               #(assoc %1 retag %2)
               retag)]
     (reify
       Specize
       (specize* [s] s)
       (specize* [s _] s)

       Spec
       (conform* [_ x] (if-let [pred (predx x)]
                         (dt pred x form)
                         invalid))
       (unform* [_ x] (if-let [pred (predx x)]
                        (unform pred x)
                        (throw (IllegalStateException. (str "No method of: " form " for dispatch value: " (dval x))))))
       (explain* [_ path via in x]
         (let [dv (dval x)
               path (conj path dv)]
           (if-let [pred (predx x)]
             (explain-1 form pred path via in x)
             [{:path path :pred form :val x :reason "no method" :via via :in in}])))
       (gen* [_ overrides path rmap]
         (if gfn
           (gfn)
           (let [gen (fn [[k f]]
                       (let [p (f nil)]
                         (let [rmap (inck rmap id)]
                           (when-not (recur-limit? rmap id path k)
                             (gen/delay-internal
                               (gen/fmap
                                #(tag % k)
                                (gensub p overrides (conj path k) rmap (list 'method form k))))))))
                 gs (->> (methods @mmvar)
                         (remove (fn [[k]] (invalid? k)))
                         (map gen)
                         (remove nil?))]
             (when (every? identity gs)
               (gen/one-of gs)))))
       (with-gen* [_ gfn] (multi-spec-impl form mmvar retag gfn))
       (describe* [_] `(clojure.spec.alpha/multi-spec ~form ~retag))))))

(defn ^:skip-wiki tuple-impl
  "Do not call this directly, use 'tuple'"
  ([forms preds] (tuple-impl forms preds nil))
  ([forms preds gfn]
   (let [specs (delay (mapv specize preds forms))
         cnt (count preds)]
     (reify
       Specize
       (specize* [s] s)
       (specize* [s _] s)

       Spec
       (conform* [_ x]
         (let [specs @specs]
           (if-not (c/and (vector? x)
                          (= (count x) cnt))
             :clojure.spec.alpha/invalid
             (loop [ret x, i 0]
               (if (= i cnt)
                 ret
                 (let [v (x i)
                       cv (conform* (specs i) v)]
                   (if (invalid? cv)
                     :clojure.spec.alpha/invalid
                     (recur (if (identical? cv v) ret (assoc ret i cv))
                            (inc i)))))))))
       (unform* [_ x]
         (c/assert (c/and (vector? x)
                          (= (count x) (count preds))))
         (loop [ret x, i 0]
           (if (= i (count x))
             ret
             (let [cv (x i)
                   v (unform (preds i) cv)]
               (recur (if (identical? cv v) ret (assoc ret i v))
                      (inc i))))))
       (explain* [_ path via in x]
         (cond
           (not (vector? x))
           [{:path path :pred `vector? :val x :via via :in in}]

           (not= (count x) (count preds))
           [{:path path :pred `(= (count ~'%) ~(count preds)) :val x :via via :in in}]

           :else
           (apply concat
                  (map (fn [i form pred]
                         (let [v (x i)]
                           (when-not (pvalid? pred v)
                             (explain-1 form pred (conj path i) via (conj in i) v))))
                       (range (count preds)) forms preds))))
       (gen* [_ overrides path rmap]
         (if gfn
           (gfn)
           (let [gen (fn [i p f]
                       (gensub p overrides (conj path i) rmap f))
                 gs (map gen (range (count preds)) preds forms)]
             (when (every? identity gs)
               (apply gen/tuple gs)))))
       (with-gen* [_ gfn] (tuple-impl forms preds gfn))
       (describe* [_] `(clojure.spec.alpha/tuple ~@forms))))))

(defn- tagged-ret [tag ret]
  (clojure.lang.MapEntry. tag ret))

(defn ^:skip-wiki or-spec-impl
  "Do not call this directly, use 'or'"
  [keys forms preds gfn]
  (let [id (java.util.UUID/randomUUID)
        kps (zipmap keys preds)
        specs (delay (mapv specize preds forms))
        cform (case (count preds)
                2 (fn [x]
                    (let [specs @specs
                          ret (conform* (specs 0) x)]
                      (if (invalid? ret)
                        (let [ret (conform* (specs 1) x)]
                          (if (invalid? ret)
                            :clojure.spec.alpha/invalid
                            (tagged-ret (keys 1) ret)))
                        (tagged-ret (keys 0) ret))))
                3 (fn [x]
                    (let [specs @specs
                          ret (conform* (specs 0) x)]
                      (if (invalid? ret)
                        (let [ret (conform* (specs 1) x)]
                          (if (invalid? ret)
                            (let [ret (conform* (specs 2) x)]
                              (if (invalid? ret)
                                :clojure.spec.alpha/invalid
                                (tagged-ret (keys 2) ret)))
                            (tagged-ret (keys 1) ret)))
                        (tagged-ret (keys 0) ret))))
                (fn [x]
                  (let [specs @specs]
                    (loop [i 0]
                      (if (< i (count specs))
                        (let [spec (specs i)]
                          (let [ret (conform* spec x)]
                            (if (invalid? ret)
                              (recur (inc i))
                              (tagged-ret (keys i) ret))))
                        :clojure.spec.alpha/invalid)))))]
    (reify
      Specize
      (specize* [s] s)
      (specize* [s _] s)

      Spec
      (conform* [_ x] (cform x))
      (unform* [_ [k x]] (unform (kps k) x))
      (explain* [this path via in x]
        (when-not (pvalid? this x)
          (apply concat
                 (map (fn [k form pred]
                        (when-not (pvalid? pred x)
                          (explain-1 form pred (conj path k) via in x)))
                      keys forms preds))))
      (gen* [_ overrides path rmap]
        (if gfn
          (gfn)
          (let [gen (fn [k p f]
                      (let [rmap (inck rmap id)]
                        (when-not (recur-limit? rmap id path k)
                          (gen/delay-internal
                            (gensub p overrides (conj path k) rmap f)))))
                gs (remove nil? (map gen keys preds forms))]
            (when-not (empty? gs)
              (gen/one-of gs)))))
      (with-gen* [_ gfn] (or-spec-impl keys forms preds gfn))
      (describe* [_] `(clojure.spec.alpha/or ~@(mapcat vector keys forms))))))

(defn- and-preds [x preds forms]
  (loop [ret x
         [pred & preds] preds
         [form & forms] forms]
    (if pred
      (let [nret (dt pred ret form)]
        (if (invalid? nret)
          :clojure.spec.alpha/invalid
          ;;propagate conformed values
          (recur nret preds forms)))
      ret)))

(defn- explain-pred-list
  [forms preds path via in x]
  (loop [ret x
         [form & forms] forms
         [pred & preds] preds]
    (when pred
      (let [nret (dt pred ret form)]
        (if (invalid? nret)
          (explain-1 form pred path via in ret)
          (recur nret forms preds))))))

(defn ^:skip-wiki and-spec-impl
  "Do not call this directly, use 'and'"
  [forms preds gfn]
  (let [specs (delay (mapv specize preds forms))
        cform
        (case (count preds)
          2 (fn [x]
              (let [specs @specs
                    ret (conform* (specs 0) x)]
                (if (invalid? ret)
                  :clojure.spec.alpha/invalid
                  (conform* (specs 1) ret))))
          3 (fn [x]
              (let [specs @specs
                    ret (conform* (specs 0) x)]
                (if (invalid? ret)
                  :clojure.spec.alpha/invalid
                  (let [ret (conform* (specs 1) ret)]
                    (if (invalid? ret)
                      :clojure.spec.alpha/invalid
                      (conform* (specs 2) ret))))))
          (fn [x]
            (let [specs @specs]
              (loop [ret x i 0]
                (if (< i (count specs))
                  (let [nret (conform* (specs i) ret)]
                    (if (invalid? nret)
                      :clojure.spec.alpha/invalid
                      ;;propagate conformed values
                      (recur nret (inc i))))
                  ret)))))]
    (reify
      Specize
      (specize* [s] s)
      (specize* [s _] s)

      Spec
      (conform* [_ x] (cform x))
      (unform* [_ x] (reduce #(unform %2 %1) x (reverse preds)))
      (explain* [_ path via in x] (explain-pred-list forms preds path via in x))
      (gen* [_ overrides path rmap] (if gfn (gfn) (gensub (first preds) overrides path rmap (first forms))))
      (with-gen* [_ gfn] (and-spec-impl forms preds gfn))
      (describe* [_] `(clojure.spec.alpha/and ~@forms)))))

(defn ^:skip-wiki merge-spec-impl
  "Do not call this directly, use 'merge'"
  [forms preds gfn]
  (reify
    Specize
    (specize* [s] s)
    (specize* [s _] s)

    Spec
    (conform* [_ x] (let [ms (map #(dt %1 x %2) preds forms)]
                      (if (some invalid? ms)
                        :clojure.spec.alpha/invalid
                        (apply c/merge ms))))
    (unform* [_ x] (apply c/merge (map #(unform % x) (reverse preds))))
    (explain* [_ path via in x]
      (apply concat
             (map #(explain-1 %1 %2 path via in x)
                  forms preds)))
    (gen* [_ overrides path rmap]
      (if gfn
        (gfn)
        (gen/fmap
         #(apply c/merge %)
         (apply gen/tuple (map #(gensub %1 overrides path rmap %2)
                               preds forms)))))
    (with-gen* [_ gfn] (merge-spec-impl forms preds gfn))
    (describe* [_] `(clojure.spec.alpha/merge ~@forms))))

(defn- coll-prob [x kfn kform distinct count min-count max-count
                  path via in]
  (let [pred (c/or kfn coll?)
        kform (c/or kform `coll?)]
    (cond
      (not (pvalid? pred x))
      (explain-1 kform pred path via in x)

      (c/and count (not= count (bounded-count count x)))
      [{:path path :pred `(= ~count (c/count ~'%)) :val x :via via :in in}]

      (c/and (c/or min-count max-count)
             (not (<= (c/or min-count 0)
                      (bounded-count (if max-count (inc max-count) min-count) x)
                      (c/or max-count Integer/MAX_VALUE))))
      [{:path path :pred `(<= ~(c/or min-count 0) (c/count ~'%) ~(c/or max-count 'Integer/MAX_VALUE)) :val x :via via :in in}]

      (c/and distinct (not (empty? x)) (not (apply distinct? x)))
      [{:path path :pred 'distinct? :val x :via via :in in}])))

(def ^:private empty-coll {`vector? [], `set? #{}, `list? (), `map? {}})

(defn ^:skip-wiki every-impl
  "Do not call this directly, use 'every', 'every-kv', 'coll-of' or 'map-of'"
  ([form pred opts] (every-impl form pred opts nil))
  ([form pred {conform-into :into
               describe-form :clojure.spec.alpha/describe
               :keys [kind :clojure.spec.alpha/kind-form count max-count min-count distinct gen-max :clojure.spec.alpha/kfn :clojure.spec.alpha/cpred
                      conform-keys :clojure.spec.alpha/conform-all]
               :or {gen-max 20}
               :as opts}
    gfn]
   (let [gen-into (if conform-into (empty conform-into) (get empty-coll kind-form))
         spec (delay (specize pred))
         check? #(valid? @spec %)
         kfn (c/or kfn (fn [i v] i))
         addcv (fn [ret i v cv] (conj ret cv))
         cfns (fn [x]
                ;;returns a tuple of [init add complete] fns
                (cond
                  (c/and (vector? x) (c/or (not conform-into) (vector? conform-into)))
                  [identity
                   (fn [ret i v cv]
                     (if (identical? v cv)
                       ret
                       (assoc ret i cv)))
                   identity]

                  (c/and (map? x) (c/or (c/and kind (not conform-into)) (map? conform-into)))
                  [(if conform-keys empty identity)
                   (fn [ret i v cv]
                     (if (c/and (identical? v cv) (not conform-keys))
                       ret
                       (assoc ret (nth (if conform-keys cv v) 0) (nth cv 1))))
                   identity]

                  (c/or (list? conform-into) (seq? conform-into) (c/and (not conform-into) (c/or (list? x) (seq? x))))
                  [(constantly ()) addcv reverse]

                  :else [#(empty (c/or conform-into %)) addcv identity]))]
     (reify
       Specize
       (specize* [s] s)
       (specize* [s _] s)

       Spec
       (conform* [_ x]
         (let [spec @spec]
           (cond
             (not (cpred x)) :clojure.spec.alpha/invalid

             conform-all
             (let [[init add complete] (cfns x)]
               (loop [ret (init x), i 0, [v & vs :as vseq] (seq x)]
                 (if vseq
                   (let [cv (conform* spec v)]
                     (if (invalid? cv)
                       :clojure.spec.alpha/invalid
                       (recur (add ret i v cv) (inc i) vs)))
                   (complete ret))))


             :else
             (if (indexed? x)
               (let [step (max 1 (long (/ (c/count x) *coll-check-limit*)))]
                 (loop [i 0]
                   (if (>= i (c/count x))
                     x
                     (if (valid? spec (nth x i))
                       (recur (c/+ i step))
                       :clojure.spec.alpha/invalid))))
               (let [limit *coll-check-limit*]
                 (loop [i 0 [v & vs :as vseq] (seq x)]
                   (cond
                     (c/or (nil? vseq) (= i limit)) x
                     (valid? spec v) (recur (inc i) vs)
                     :else :clojure.spec.alpha/invalid)))))))
       (unform* [_ x]
         (if conform-all
           (let [spec @spec
                 [init add complete] (cfns x)]
             (loop [ret (init x), i 0, [v & vs :as vseq] (seq x)]
               (if (>= i (c/count x))
                 (complete ret)
                 (recur (add ret i v (unform* spec v)) (inc i) vs))))
           x))
       (explain* [_ path via in x]
         (c/or (coll-prob x kind kind-form distinct count min-count max-count
                          path via in)
               (apply concat
                      ((if conform-all identity (partial take *coll-error-limit*))
                       (keep identity
                             (map (fn [i v]
                                    (let [k (kfn i v)]
                                      (when-not (check? v)
                                        (let [prob (explain-1 form pred path via (conj in k) v)]
                                          prob))))
                                  (range) x))))))
       (gen* [_ overrides path rmap]
         (if gfn
           (gfn)
           (let [pgen (gensub pred overrides path rmap form)]
             (gen/bind
              (cond
                gen-into (gen/return gen-into)
                kind (gen/fmap #(if (empty? %) % (empty %))
                               (gensub kind overrides path rmap form))
                :else (gen/return []))
              (fn [init]
                (gen/fmap
                 #(if (vector? init) % (into init %))
                 (cond
                   distinct
                   (if count
                     (gen/vector-distinct pgen {:num-elements count :max-tries 100})
                     (gen/vector-distinct pgen {:min-elements (c/or min-count 0)
                                                :max-elements (c/or max-count (max gen-max (c/* 2 (c/or min-count 0))))
                                                :max-tries 100}))

                   count
                   (gen/vector pgen count)

                   (c/or min-count max-count)
                   (gen/vector pgen (c/or min-count 0) (c/or max-count (max gen-max (c/* 2 (c/or min-count 0)))))

                   :else
                   (gen/vector pgen 0 gen-max))))))))

       (with-gen* [_ gfn] (every-impl form pred opts gfn))
       (describe* [_] (c/or describe-form `(clojure.spec.alpha/every ~(res form) ~@(mapcat identity opts))))))))

;;;;;;;;;;;;;;;;;;;;;;; regex ;;;;;;;;;;;;;;;;;;;
;;See:
;; http://matt.might.net/articles/implementation-of-regular-expression-matching-in-scheme-with-derivatives/
;; http://www.ccs.neu.edu/home/turon/re-deriv.pdf

;;ctors
(defn- accept [x] {:clojure.spec.alpha/op :clojure.spec.alpha/accept :ret x})

(defn- accept? [{:keys [:clojure.spec.alpha/op]}]
  (= :clojure.spec.alpha/accept op))

(defn- pcat* [{[p1 & pr :as ps] :ps,  [k1 & kr :as ks] :ks, [f1 & fr :as forms] :forms, ret :ret, rep+ :rep+}]
  (when (every? identity ps)
    (if (accept? p1)
      (let [rp (:ret p1)
            ret (conj ret (if ks {k1 rp} rp))]
        (if pr
          (pcat* {:ps pr :ks kr :forms fr :ret ret})
          (accept ret)))
      {:clojure.spec.alpha/op :clojure.spec.alpha/pcat, :ps ps, :ret ret, :ks ks, :forms forms :rep+ rep+})))

(defn- pcat [& ps] (pcat* {:ps ps :ret []}))

(defn ^:skip-wiki cat-impl
  "Do not call this directly, use 'cat'"
  [ks ps forms]
  (pcat* {:ks ks, :ps ps, :forms forms, :ret {}}))

(defn- rep* [p1 p2 ret splice form]
  (when p1
    (let [r {:clojure.spec.alpha/op :clojure.spec.alpha/rep, :p2 p2, :splice splice, :forms form :id (java.util.UUID/randomUUID)}]
      (if (accept? p1)
        (assoc r :p1 p2 :ret (conj ret (:ret p1)))
        (assoc r :p1 p1, :ret ret)))))

(defn ^:skip-wiki rep-impl
  "Do not call this directly, use '*'"
  [form p] (rep* p p [] false form))

(defn ^:skip-wiki rep+impl
  "Do not call this directly, use '+'"
  [form p]
  (pcat* {:ps [p (rep* p p [] true form)] :forms `[~form (* ~form)] :ret [] :rep+ form}))

(defn ^:skip-wiki amp-impl
  "Do not call this directly, use '&'"
  [re re-form preds pred-forms]
  {:clojure.spec.alpha/op :clojure.spec.alpha/amp :p1 re :amp re-form :ps preds :forms pred-forms})

(defn- filter-alt [ps ks forms f]
  (if (c/or ks forms)
    (let [pks (->> (map vector ps
                        (c/or (seq ks) (repeat nil))
                        (c/or (seq forms) (repeat nil)))
                   (filter #(-> % first f)))]
      [(seq (map first pks)) (when ks (seq (map second pks))) (when forms (seq (map #(nth % 2) pks)))])
    [(seq (filter f ps)) ks forms]))

(defn- alt* [ps ks forms]
  (let [[[p1 & pr :as ps] [k1 :as ks] forms] (filter-alt ps ks forms identity)]
    (when ps
      (let [ret {:clojure.spec.alpha/op :clojure.spec.alpha/alt, :ps ps, :ks ks :forms forms}]
        (if (nil? pr)
          (if k1
            (if (accept? p1)
              (accept (tagged-ret k1 (:ret p1)))
              ret)
            p1)
          ret)))))

(defn- alts [& ps] (alt* ps nil nil))
(defn- alt2 [p1 p2] (if (c/and p1 p2) (alts p1 p2) (c/or p1 p2)))

(defn ^:skip-wiki alt-impl
  "Do not call this directly, use 'alt'"
  [ks ps forms] (assoc (alt* ps ks forms) :id (java.util.UUID/randomUUID)))

(defn ^:skip-wiki maybe-impl
  "Do not call this directly, use '?'"
  [p form] (assoc (alt* [p (accept :clojure.spec.alpha/nil)] nil [form :clojure.spec.alpha/nil]) :maybe form))

(defn- noret? [p1 pret]
  (c/or (= pret :clojure.spec.alpha/nil)
        (c/and (#{:clojure.spec.alpha/rep :clojure.spec.alpha/pcat} (:clojure.spec.alpha/op (reg-resolve! p1))) ;;hrm, shouldn't know these
               (empty? pret))
        nil))

(declare preturn)

(defn- accept-nil? [p]
  (let [{:keys [:clojure.spec.alpha/op ps p1 p2 forms] :as p} (reg-resolve! p)]
    (case op
      :clojure.spec.alpha/accept true
      nil nil
      :clojure.spec.alpha/amp (c/and (accept-nil? p1)
                   (let [ret (-> (preturn p1) (and-preds ps (next forms)))]
                     (not (invalid? ret))))
      :clojure.spec.alpha/rep (c/or (identical? p1 p2) (accept-nil? p1))
      :clojure.spec.alpha/pcat (every? accept-nil? ps)
      :clojure.spec.alpha/alt (c/some accept-nil? ps))))

(declare add-ret)

(defn- preturn [p]
  (let [{[p0 & pr :as ps] :ps, [k :as ks] :ks, :keys [:clojure.spec.alpha/op p1 ret forms] :as p} (reg-resolve! p)]
    (case op
      :clojure.spec.alpha/accept ret
      nil nil
      :clojure.spec.alpha/amp (let [pret (preturn p1)]
              (if (noret? p1 pret)
                :clojure.spec.alpha/nil
                (and-preds pret ps forms)))
      :clojure.spec.alpha/rep (add-ret p1 ret k)
      :clojure.spec.alpha/pcat (add-ret p0 ret k)
      :clojure.spec.alpha/alt (let [[[p0] [k0]] (filter-alt ps ks forms accept-nil?)
                  r (if (nil? p0) :clojure.spec.alpha/nil (preturn p0))]
              (if k0 (tagged-ret k0 r) r)))))

(defn- op-unform [p x]
  ;;(prn {:p p :x x})
  (let [{[p0 & pr :as ps] :ps, [k :as ks] :ks, :keys [:clojure.spec.alpha/op p1 ret forms rep+ maybe] :as p} (reg-resolve! p)
        kps (zipmap ks ps)]
    (case op
      :clojure.spec.alpha/accept [ret]
      nil [(unform p x)]
      :clojure.spec.alpha/amp (let [px (reduce #(unform %2 %1) x (reverse ps))]
              (op-unform p1 px))
      :clojure.spec.alpha/rep (mapcat #(op-unform p1 %) x)
      :clojure.spec.alpha/pcat (if rep+
               (mapcat #(op-unform p0 %) x)
               (mapcat (fn [k]
                         (when (contains? x k)
                           (op-unform (kps k) (get x k))))
                       ks))
      :clojure.spec.alpha/alt (if maybe
              [(unform p0 x)]
              (let [[k v] x]
                (op-unform (kps k) v))))))

(defn- add-ret [p r k]
  (let [{:keys [:clojure.spec.alpha/op ps splice] :as p} (reg-resolve! p)
        prop #(let [ret (preturn p)]
                (if (empty? ret) r ((if splice into conj) r (if k {k ret} ret))))]
    (case op
      nil r
      (:clojure.spec.alpha/alt :clojure.spec.alpha/accept :clojure.spec.alpha/amp)
      (let [ret (preturn p)]
        ;;(prn {:ret ret})
        (if (= ret :clojure.spec.alpha/nil) r (conj r (if k {k ret} ret))))

      (:clojure.spec.alpha/rep :clojure.spec.alpha/pcat) (prop))))

(defn- deriv
  [p x]
  (let [{[p0 & pr :as ps] :ps, [k0 & kr :as ks] :ks, :keys [:clojure.spec.alpha/op p1 p2 ret splice forms amp] :as p} (reg-resolve! p)]
    (when p
      (case op
        :clojure.spec.alpha/accept nil
        nil (let [ret (dt p x p)]
              (when-not (invalid? ret) (accept ret)))
        :clojure.spec.alpha/amp (when-let [p1 (deriv p1 x)]
                (if (= :clojure.spec.alpha/accept (:clojure.spec.alpha/op p1))
                  (let [ret (-> (preturn p1) (and-preds ps (next forms)))]
                    (when-not (invalid? ret)
                      (accept ret)))
                  (amp-impl p1 amp ps forms)))
        :clojure.spec.alpha/pcat (alt2 (pcat* {:ps (cons (deriv p0 x) pr), :ks ks, :forms forms, :ret ret})
                     (when (accept-nil? p0) (deriv (pcat* {:ps pr, :ks kr, :forms (next forms), :ret (add-ret p0 ret k0)}) x)))
        :clojure.spec.alpha/alt (alt* (map #(deriv % x) ps) ks forms)
        :clojure.spec.alpha/rep (alt2 (rep* (deriv p1 x) p2 ret splice forms)
                    (when (accept-nil? p1) (deriv (rep* p2 p2 (add-ret p1 ret nil) splice forms) x)))))))

(defn- op-describe [p]
  (let [{:keys [:clojure.spec.alpha/op ps ks forms splice p1 rep+ maybe amp] :as p} (reg-resolve! p)]
    ;;(prn {:op op :ks ks :forms forms :p p})
    (when p
      (case op
        :clojure.spec.alpha/accept nil
        nil p
        :clojure.spec.alpha/amp (list* 'clojure.spec.alpha/& amp forms)
        :clojure.spec.alpha/pcat (if rep+
                 (list 'clojure.spec.alpha/+ rep+)
                 (cons 'clojure.spec.alpha/cat (mapcat vector (c/or (seq ks) (repeat :_)) forms)))
        :clojure.spec.alpha/alt (if maybe
                (list 'clojure.spec.alpha/? maybe)
                (cons 'clojure.spec.alpha/alt (mapcat vector ks forms)))
        :clojure.spec.alpha/rep (list (if splice 'clojure.spec.alpha/+ 'clojure.spec.alpha/*) forms)))))

(defn- op-explain [form p path via in input]
  ;;(prn {:form form :p p :path path :input input})
  (let [[x :as input] input
        {:keys [:clojure.spec.alpha/op ps ks forms splice p1 p2] :as p} (reg-resolve! p)
        via (if-let [name (spec-name p)] (conj via name) via)
        insufficient (fn [path form]
                       [{:path path
                         :reason "Insufficient input"
                         :pred form
                         :val ()
                         :via via
                         :in in}])]
    (when p
      (case op
        :clojure.spec.alpha/accept nil
        nil (if (empty? input)
              (insufficient path form)
              (explain-1 form p path via in x))
        :clojure.spec.alpha/amp (if (empty? input)
                (if (accept-nil? p1)
                  (explain-pred-list forms ps path via in (preturn p1))
                  (insufficient path (:amp p)))
                (if-let [p1 (deriv p1 x)]
                  (explain-pred-list forms ps path via in (preturn p1))
                  (op-explain (:amp p) p1 path via in input)))
        :clojure.spec.alpha/pcat (let [pkfs (map vector
                               ps
                               (c/or (seq ks) (repeat nil))
                               (c/or (seq forms) (repeat nil)))
                     [pred k form] (if (= 1 (count pkfs))
                                     (first pkfs)
                                     (first (remove (fn [[p]] (accept-nil? p)) pkfs)))
                     path (if k (conj path k) path)
                     form (c/or form (op-describe pred))]
                 (if (c/and (empty? input) (not pred))
                   (insufficient path form)
                   (op-explain form pred path via in input)))
        :clojure.spec.alpha/alt (if (empty? input)
                (insufficient path (op-describe p))
                (apply concat
                       (map (fn [k form pred]
                              (op-explain (c/or form (op-describe pred))
                                          pred
                                          (if k (conj path k) path)
                                          via
                                          in
                                          input))
                            (c/or (seq ks) (repeat nil))
                            (c/or (seq forms) (repeat nil))
                            ps)))
        :clojure.spec.alpha/rep (op-explain (if (identical? p1 p2)
                            forms
                            (op-describe p1))
                          p1 path via in input)))))

(defn- re-gen [p overrides path rmap f]
  ;;(prn {:op op :ks ks :forms forms})
  (let [origp p
        {:keys [:clojure.spec.alpha/op ps ks p1 p2 forms splice ret id :clojure.spec.alpha/gfn] :as p} (reg-resolve! p)
        rmap (if id (inck rmap id) rmap)
        ggens (fn [ps ks forms]
                (let [gen (fn [p k f]
                            ;;(prn {:k k :path path :rmap rmap :op op :id id})
                            (when-not (c/and rmap id k (recur-limit? rmap id path k))
                              (if id
                                (gen/delay-internal (re-gen p overrides (if k (conj path k) path) rmap (c/or f p)))
                                (re-gen p overrides (if k (conj path k) path) rmap (c/or f p)))))]
                  (map gen ps (c/or (seq ks) (repeat nil)) (c/or (seq forms) (repeat nil)))))]
    (c/or (when-let [gfn (c/or (get overrides (spec-name origp))
                               (get overrides (spec-name p) )
                               (get overrides path))]
            (case op
              (:accept nil) (gen/fmap vector (gfn))
              (gfn)))
          (when gfn
            (gfn))
          (when p
            (case op
              :clojure.spec.alpha/accept (if (= ret :clojure.spec.alpha/nil)
                         (gen/return [])
                         (gen/return [ret]))
              nil (when-let [g (gensub p overrides path rmap f)]
                    (gen/fmap vector g))
              :clojure.spec.alpha/amp (re-gen p1 overrides path rmap (op-describe p1))
              :clojure.spec.alpha/pcat (let [gens (ggens ps ks forms)]
                       (when (every? identity gens)
                         (apply gen/cat gens)))
              :clojure.spec.alpha/alt (let [gens (remove nil? (ggens ps ks forms))]
                      (when-not (empty? gens)
                        (gen/one-of gens)))
              :clojure.spec.alpha/rep (if (recur-limit? rmap id [id] id)
                      (gen/return [])
                      (when-let [g (re-gen p2 overrides path rmap forms)]
                        (gen/fmap #(apply concat %)
                                  (gen/vector g)))))))))

(defn- re-conform [p [x & xs :as data]]
  ;;(prn {:p p :x x :xs xs})
  (if (empty? data)
    (if (accept-nil? p)
      (let [ret (preturn p)]
        (if (= ret :clojure.spec.alpha/nil)
          nil
          ret))
      invalid)
    (if-let [dp (deriv p x)]
      (recur dp xs)
      invalid)))

(defn- re-explain [path via in re input]
  (loop [p re [x & xs :as data] input i 0]
    ;;(prn {:p p :x x :xs xs :re re}) (prn)
    (if (empty? data)
      (if (accept-nil? p)
        nil ;;success
        (op-explain (op-describe p) p path via in nil))
      (if-let [dp (deriv p x)]
        (recur dp xs (inc i))
        (if (accept? p)
          (if (= (:clojure.spec.alpha/op p) :clojure.spec.alpha/pcat)
            (op-explain (op-describe p) p path via (conj in i) (seq data))
            [{:path path
              :reason "Extra input"
              :pred (op-describe re)
              :val data
              :via via
              :in (conj in i)}])
          (c/or (op-explain (op-describe p) p path via (conj in i) (seq data))
                [{:path path
                  :reason "Extra input"
                  :pred (op-describe p)
                  :val data
                  :via via
                  :in (conj in i)}]))))))

(defn ^:skip-wiki regex-spec-impl
  "Do not call this directly, use 'spec' with a regex op argument"
  [re gfn]
  (reify
    Specize
    (specize* [s] s)
    (specize* [s _] s)

    Spec
    (conform* [_ x]
      (if (c/or (nil? x) (sequential? x))
        (re-conform re (seq x))
        :clojure.spec.alpha/invalid))
    (unform* [_ x] (op-unform re x)) ;; so far OK
    (explain* [_ path via in x]
      (if (c/or (nil? x) (sequential? x))
        (re-explain path via in re (seq x))
        [{:path path :pred (res `#(c/or (nil? %) (sequential? %))) :val x :via via :in in}]))
    (gen* [_ overrides path rmap]
      (if gfn
        (gfn)
        (re-gen re overrides path rmap (op-describe re))))
    (with-gen* [_ gfn] (regex-spec-impl re gfn))
    (describe* [_] (op-describe re))))

;;;;;;;;;;;;;;;;; HOFs ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- call-valid?
  [f specs args]
  (let [cargs (conform (:args specs) args)]
    (when-not (invalid? cargs)
      (let [ret (apply f args)
            cret (conform (:ret specs) ret)]
        (c/and (not (invalid? cret))
               (if (:fn specs)
                 (pvalid? (:fn specs) {:args cargs :ret cret})
                 true))))))

(defn- validate-fn
  "returns f if valid, else smallest"
  [f specs iters]
  (let [g (gen (:args specs))
        prop (gen/for-all* [g] #(call-valid? f specs %))]
    (let [ret (gen/quick-check iters prop)]
      (if-let [[smallest] (-> ret :shrunk :smallest)]
        smallest
        f))))

(defn ^:skip-wiki fspec-impl
  "Do not call this directly, use 'fspec'"
  [argspec aform retspec rform fnspec fform gfn]
  (let [specs {:args argspec :ret retspec :fn fnspec}]
    (reify
      clojure.lang.ILookup
      (valAt [this k] (get specs k))
      (valAt [_ k not-found] (get specs k not-found))

      Specize
      (specize* [s] s)
      (specize* [s _] s)

      Spec
      (conform* [this f] (if argspec
                           (if (ifn? f)
                             (if (identical? f (validate-fn f specs *fspec-iterations*)) f :clojure.spec.alpha/invalid)
                             :clojure.spec.alpha/invalid)
                           (throw (Exception. (str "Can't conform fspec without args spec: " (pr-str (describe this)))))))
      (unform* [_ f] f)
      (explain* [_ path via in f]
        (if (ifn? f)
          (let [args (validate-fn f specs 100)]
            (if (identical? f args) ;;hrm, we might not be able to reproduce
              nil
              (let [ret (try (apply f args) (catch Throwable t t))]
                (if (instance? Throwable ret)
                  ;;TODO add exception data
                  [{:path path :pred '(apply fn) :val args :reason (.getMessage ^Throwable ret) :via via :in in}]

                  (let [cret (dt retspec ret rform)]
                    (if (invalid? cret)
                      (explain-1 rform retspec (conj path :ret) via in ret)
                      (when fnspec
                        (let [cargs (conform argspec args)]
                          (explain-1 fform fnspec (conj path :fn) via in {:args cargs :ret cret})))))))))
          [{:path path :pred 'ifn? :val f :via via :in in}]))
      (gen* [_ overrides _ _] (if gfn
                                (gfn)
                                (gen/return
                                 (fn [& args]
                                   (c/assert (pvalid? argspec args) (with-out-str (explain argspec args)))
                                   (gen/generate (gen retspec overrides))))))
      (with-gen* [_ gfn] (fspec-impl argspec aform retspec rform fnspec fform gfn))
      (describe* [_] `(clojure.spec.alpha/fspec :args ~aform :ret ~rform :fn ~fform)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; non-primitives ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(internal-def
  :clojure.spec.alpha/kvs->map
  (internal-conformer #(zipmap (map :clojure.spec.alpha/k %) (map :clojure.spec.alpha/v %)) #(map (fn [[k v]] {:clojure.spec.alpha/k k :clojure.spec.alpha/v v}) %)))

(defmacro keys*
  "takes the same arguments as spec/keys and returns a regex op that matches sequences of key/values,
  converts them into a map, and conforms that map with a corresponding
  spec/keys call:

  user=> (s/conform (s/keys :req-un [:clojure.spec.alpha/a :clojure.spec.alpha/c]) {:a 1 :c 2})
  {:a 1, :c 2}
  user=> (s/conform (s/keys* :req-un [:clojure.spec.alpha/a :clojure.spec.alpha/c]) [:a 1 :c 2])
  {:a 1, :c 2}

  the resulting regex op can be composed into a larger regex:

  user=> (s/conform (s/cat :i1 integer? :m (s/keys* :req-un [:clojure.spec.alpha/a :clojure.spec.alpha/c]) :i2 integer?) [42 :a 1 :c 2 :d 4 99])
  {:i1 42, :m {:a 1, :c 2, :d 4}, :i2 99}"
  [& kspecs]
  `(let [mspec# (clojure.spec.alpha/keys ~@kspecs)]
     (clojure.spec.alpha/with-gen (clojure.spec.alpha/& (clojure.spec.alpha/* (clojure.spec.alpha/cat :clojure.spec.alpha/k keyword? :clojure.spec.alpha/v any?)) :clojure.spec.alpha/kvs->map mspec#)
       (fn [] (clojure.spec.gen.alpha/fmap (fn [m#] (apply concat m#)) (clojure.spec.alpha/gen mspec#))))))

(defn ^:skip-wiki nonconforming
  "takes a spec and returns a spec that has the same properties except
  'conform' returns the original (not the conformed) value. Note, will specize regex ops."
  [spec]
  (let [spec (delay (specize spec))]
    (reify
      Specize
      (specize* [s] s)
      (specize* [s _] s)

      Spec
      (conform* [_ x] (let [ret (conform* @spec x)]
                        (if (invalid? ret)
                          :clojure.spec.alpha/invalid
                          x)))
      (unform* [_ x] x)
      (explain* [_ path via in x] (explain* @spec path via in x))
      (gen* [_ overrides path rmap] (gen* @spec overrides path rmap))
      (with-gen* [_ gfn] (nonconforming (with-gen* @spec gfn)))
      (describe* [_] `(clojure.spec.alpha/nonconforming ~(describe* @spec))))))

(defn ^:skip-wiki nilable-impl
  "Do not call this directly, use 'nilable'"
  [form pred gfn]
  (let [spec (delay (specize pred form))]
    (reify
      Specize
      (specize* [s] s)
      (specize* [s _] s)

      Spec
      (conform* [_ x] (if (nil? x) nil (conform* @spec x)))
      (unform* [_ x] (if (nil? x) nil (unform* @spec x)))
      (explain* [_ path via in x]
        (when-not (c/or (pvalid? @spec x) (nil? x))
          (conj
           (explain-1 form pred (conj path :clojure.spec.alpha/pred) via in x)
           {:path (conj path :clojure.spec.alpha/nil) :pred 'nil? :val x :via via :in in})))
      (gen* [_ overrides path rmap]
        (if gfn
          (gfn)
          (gen/frequency
           [[1 (gen/delay-internal (gen/return nil))]
            [9 (gen/delay-internal (gensub pred overrides (conj path :clojure.spec.alpha/pred) rmap form))]])))
      (with-gen* [_ gfn] (nilable-impl form pred gfn))
      (describe* [_] `(clojure.spec.alpha/nilable ~(res form))))))

(defmacro nilable
  "returns a spec that accepts nil and values satisfying pred"
  [pred]
  (let [pf (res pred)]
    `(clojure.spec.alpha/nilable-impl '~pf ~pred nil)))

(defn exercise
  "generates a number (default 10) of values compatible with spec and maps conform over them,
  returning a sequence of [val conformed-val] tuples. Optionally takes
  a generator overrides map as per gen"
  ([spec] (exercise spec 10))
  ([spec n] (exercise spec n nil))
  ([spec n overrides]
   (map #(vector % (conform spec %)) (gen/sample (gen spec overrides) n))))

(defn exercise-fn
  "exercises the fn named by sym (a symbol) by applying it to
  n (default 10) generated samples of its args spec. When fspec is
  supplied its arg spec is used, and sym-or-f can be a fn.  Returns a
  sequence of tuples of [args ret]. "
  ([sym] (exercise-fn sym 10))
  ([sym n] (exercise-fn sym n (get-spec sym)))
  ([sym-or-f n fspec]
   (let [f (if (symbol? sym-or-f) (resolve sym-or-f) sym-or-f)]
     (if-let [arg-spec (c/and fspec (:args fspec))]
       (for [args (gen/sample (gen arg-spec) n)]
         [args (apply f args)])
       (throw (Exception. "No :args spec found, can't generate"))))))

(defn inst-in-range?
  "Return true if inst at or after start and before end"
  [start end inst]
  (c/and (inst? inst)
         (let [t (inst-ms inst)]
           (c/and (<= (inst-ms start) t) (< t (inst-ms end))))))

(defmacro inst-in
  "Returns a spec that validates insts in the range from start
  (inclusive) to end (exclusive)."
  [start end]
  `(let [st# (inst-ms ~start)
         et# (inst-ms ~end)
         mkdate# (fn [d#] (java.util.Date. ^{:tag ~'long} d#))]
     (clojure.spec.alpha/spec
      (clojure.spec.alpha/and inst? #(clojure.spec.alpha/inst-in-range? ~start ~end %))
      :gen (fn []
             (clojure.spec.gen.alpha/fmap mkdate#
                       (clojure.spec.gen.alpha/large-integer* {:min st# :max et#}))))))

(defn int-in-range?
  "Return true if start <= val, val < end and val is a fixed
  precision integer."
  [start end val]
  (c/and (int? val) (<= start val) (< val end)))

(defmacro int-in
  "Returns a spec that validates fixed precision integers in the
  range from start (inclusive) to end (exclusive)."
  [start end]
  `(clojure.spec.alpha/spec
    (clojure.spec.alpha/and int? #(clojure.spec.alpha/int-in-range? ~start ~end %))
    :gen #(clojure.spec.gen.alpha/large-integer* {:min ~start :max (dec ~end)})))

(defmacro double-in
  "Specs a 64-bit floating point number. Options:

    :infinite? - whether +/- infinity allowed (default true)
    :NaN?      - whether NaN allowed (default true)
    :min       - minimum value (inclusive, default none)
    :max       - maximum value (inclusive, default none)"
  [& {:keys [infinite? NaN? min max]
      :or {infinite? true NaN? true}
      :as m}]
  `(clojure.spec.alpha/spec (clojure.spec.alpha/and c/double?
              ~@(when-not infinite? '[#(not (Double/isInfinite %))])
              ~@(when-not NaN? '[#(not (Double/isNaN %))])
              ~@(when max `[#(<= % ~max)])
              ~@(when min `[#(<= ~min %)]))
         :gen #(clojure.spec.gen.alpha/double* ~m)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; assert ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defonce
  ^{:dynamic true
    :doc "If true, compiler will enable spec asserts, which are then
subject to runtime control via check-asserts? If false, compiler
will eliminate all spec assert overhead. See 'assert'.

Initially set to boolean value of clojure.spec.compile-asserts
system property. Defaults to true."}
  *compile-asserts*
  (not= "false" (System/getProperty "clojure.spec.compile-asserts")))

(defn check-asserts?
  "Returns the value set by check-asserts."
  []
  clojure.lang.RT/checkSpecAsserts)

(defn check-asserts
  "Enable or disable spec asserts that have been compiled
  with '*compile-asserts*' true.  See 'assert'.

  Initially set to boolean value of clojure.spec.check-asserts
  system property. Defaults to false."
  [flag]
  (set! (. clojure.lang.RT checkSpecAsserts) flag))

(defn assert*
  "Do not call this directly, use 'assert'."
  [spec x]
  (if (valid? spec x)
    x
    (let [ed (c/merge (assoc (explain-data* spec [] [] [] x)
                             :clojure.spec.alpha/failure :assertion-failed))]
      (throw (ex-info
              (str "Spec assertion failed\n" (with-out-str (explain-out ed)))
              ed)))))

(defmacro assert
  "spec-checking assert expression. Returns x if x is valid? according
  to spec, else throws an ex-info with explain-data plus :clojure.spec.alpha/failure of
  :assertion-failed.

  Can be disabled at either compile time or runtime:

  If *compile-asserts* is false at compile time, compiles to x. Defaults
  to value of 'clojure.spec.compile-asserts' system property, or true if
  not set.

  If (check-asserts?) is false at runtime, always returns x. Defaults to
  value of 'clojure.spec.check-asserts' system property, or false if not
  set. You can toggle check-asserts? with (check-asserts bool)."
  [spec x]
  (if *compile-asserts*
    `(if clojure.lang.RT/checkSpecAsserts
       (clojure.spec.alpha/assert* ~spec ~x)
       ~x)
    x))
