(ns babashka.interpreter
  {:no-doc true}
  (:refer-clojure :exclude [comparator])
  (:require [clojure.walk :refer [postwalk]]
            [clojure.string :as str]
            [clojure.set :as set]))

(def syms '(= < <= >= + +' - * /
              aget alength apply assoc assoc-in
              bit-set bit-shift-left bit-shift-right bit-xor boolean boolean? booleans boolean-array butlast
              char char? conj cons contains? count
              dec dec' decimal? dedupe dissoc distinct disj drop
              eduction even? every?
              get
              first float? floats fnil
              identity inc int-array iterate
              juxt
              filter filterv find frequencies
              last line-seq
              keep keep-indexed keys
              map mapv map-indexed mapcat merge merge-with munge
              name newline not= num
              neg? nth nthrest
              odd?
              peek pos?
              re-seq re-find re-pattern rest reverse
              set? sequential? some? str
              take take-last take-nth tree-seq type
              unchecked-inc-int unchecked-long unchecked-negate unchecked-remainder-int
              unchecked-subtract-int unsigned-bit-shift-right unchecked-float
              vals vec vector?
              rand-int rand-nth range reduce reduced? remove
              second set seq seq? shuffle simple-symbol? sort sort-by subs
              set/difference set/join
              str/join str/starts-with? str/ends-with? str/split
              zero?))

;; TODO:
#_(def all-syms
  '#{when-first while if-not when-let inc' cat StackTraceElement->vec flush take-while vary-meta <= alter -' if-some conj! repeatedly zipmap reset-vals! alter-var-root biginteger remove * re-pattern min pop! chunk-append prn-str with-precision format reversible? shutdown-agents conj bound? transduce lazy-seq *print-length* *file* compare-and-set! *use-context-classloader* await1 let ref-set pop-thread-bindings interleave printf map? -> defstruct *err* assert-same-protocol get doto identity into areduce long double volatile? definline nfirst meta find-protocol-impl bit-and-not *default-data-reader-fn* var? method-sig unchecked-add-int unquote-splicing hash-ordered-coll future reset-meta! Vec cycle fn seque empty? short definterface add-tap filterv hash quot ns-aliases read unchecked-double key longs not= string? uri? aset-double unchecked-multiply-int chunk-rest pcalls *allow-unresolved-vars* remove-all-methods ns-resolve as-> aset-boolean trampoline double? when-not *1 vec *print-meta* when int map-entry? ns-refers rand second vector-of hash-combine > replace int? associative? unchecked-int set-error-handler! inst-ms* keyword? force bound-fn* namespace-munge group-by prn extend unchecked-multiply some->> default-data-readers ->VecSeq even? unchecked-dec Inst tagged-literal? double-array in-ns create-ns re-matcher defn ref bigint extends? promise aset-char rseq ex-cause construct-proxy agent-errors *compile-files* ex-message *math-context* float pr-str concat aset-short set-agent-send-off-executor! ns symbol to-array-2d mod amap pop use VecNode unquote declare dissoc! reductions aset-byte indexed? ref-history-count - assoc! hash-set reduce-kv or cast reset! name ffirst sorted-set counted? byte-array IVecImpl tagged-literal println extend-type macroexpand-1 assoc-in char-name-string bit-test defmethod requiring-resolve EMPTY-NODE time memoize alter-meta! future? zero? simple-keyword? require unchecked-dec-int persistent! nnext add-watch not-every? class? rem agent-error some future-cancelled? memfn neg-int? struct-map drop *data-readers* nth sorted? nil? extend-protocol split-at *e load-reader random-sample cond-> dotimes select-keys bit-and bounded-count update list* reify update-in prefer-method aset-int *clojure-version* ensure-reduced *' instance? with-open mix-collection-hash re-find run! val defonce unchecked-add loaded-libs ->Vec bytes? not with-meta unreduced the-ns record? type identical? unchecked-divide-int ns-name max-key *unchecked-math* defn- *out* file-seq agent ns-map set-validator! ident? defprotocol swap! vals unchecked-subtract tap> *warn-on-reflection* sorted-set-by sync qualified-ident? assert *compile-path* true? release-pending-sends print empty remove-method *in* print-ctor letfn volatile! / read-line reader-conditional? bit-or clear-agent-errors vector proxy-super >= drop-last not-empty distinct partition loop add-classpath bit-flip long-array descendants merge accessor integer? mapv partition-all partition-by numerator object-array with-out-str condp derive load-string special-symbol? ancestors subseq error-handler gensym cond ratio? delay? intern print-simple flatten doubles halt-when with-in-str remove-watch ex-info ifn? some-> nat-int? proxy-name ns-interns all-ns find-protocol-method subvec for binding partial chunked-seq? find-keyword replicate min-key reduced char-escape-string re-matches array-map unchecked-byte with-local-vars ns-imports send-off defmacro every-pred keys rationalize load-file distinct? pos-int? extenders unchecked-short methods odd? ->ArrayChunk float-array *3 alias frequencies read-string proxy rsubseq inc get-method with-redefs uuid? bit-clear filter locking list + split-with aset ->VecNode keyword *ns* destructure *assert* defmulti chars str next hash-map if-let underive ref-max-history Throwable->map false? *print-readably* ints class some-fn case *flush-on-newline* to-array bigdec list? simple-ident? bit-not io! xml-seq VecSeq byte max == *agent* lazy-cat comment parents count supers *fn-loader* ArrayChunk sorted-map-by apply interpose deref assoc rational? transient clojure-version chunk-cons comparator sorted-map send drop-while proxy-call-with-super realized? char-array resolve compare complement *compiler-options* *print-dup* defrecord with-redefs-fn sequence constantly get-proxy-class make-array shorts completing update-proxy unchecked-negate-int hash-unordered-coll repeat unchecked-inc nthnext and create-struct get-validator number? await-for chunk-next print-str not-any? into-array qualified-symbol? init-proxy chunk-buffer seqable? symbol? when-some unchecked-char ->> future-cancel var-get commute coll? get-in fnext denominator bytes gen-and-load-class refer-clojure})

(declare var-lookup apply-fn)

(defmacro define-lookup []
  `(defn ~'var-lookup [sym#]
     (case sym#
       ~@(for [s# syms
               s# [s# s#]]
           s#)
       nil)))

(define-lookup)

(defn interpret
  [expr in]
  (cond
    (= '*in* expr) in
    (symbol? expr) (var-lookup expr)
    (list? expr)
    (if-let [f (first expr)]
      (if-let [v (var-lookup f)]
        (apply-fn v in (rest expr))
        (cond
          (or (= 'if f) (= 'when f))
          (let [[_if cond then else] expr]
            (if (interpret cond in)
              (interpret then in)
              (interpret else in)))
          ;; bb/fn passed as higher order fn, still needs input
          (-> f meta :bb/fn)
          (apply-fn (f in) in (rest expr))
          (ifn? f)
          (apply-fn f in (rest expr))
          :else nil))
      expr)
    ;; bb/fn passed as higher order fn, still needs input
    (-> expr meta :bb/fn)
    (expr in)
    :else expr))

(defn read-fn [form]
  ^:bb/fn
  (fn [in]
    (fn [& [x y z]]
      (interpret (postwalk (fn [elt]
                             (case elt
                               % x
                               %1 x
                               %2 y
                               %3 z
                               elt)) form) in))))

(defn read-regex [form]
  (re-pattern form))

(defn apply-fn [f in args]
  (let [args (mapv #(interpret % in) args)]
    (apply f args)))

;;;; Scratch

(comment
  )
