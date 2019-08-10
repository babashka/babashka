(ns babashka.interpreter
  {:no-doc true}
  (:refer-clojure :exclude [comparator])
  (:require [clojure.walk :refer [postwalk]]
            [clojure.string :as str]
            [clojure.set :as set]))

(defn expand->
  "The -> macro from clojure.core."
  [[x & forms]]
  (loop [x x, forms forms]
    (if forms
      (let [form (first forms)
            threaded (if (seq? form)
                       (with-meta (concat (list (first form) x)
                                          (next form))
                         (meta form))
                       (list form x))]
        (recur threaded (next forms)))
      x)))

(defn expand->>
  "The ->> macro from clojure.core."
  [[x & forms]]
  (loop [x x, forms forms]
    (if forms
      (let [form (first forms)
            threaded (if (seq? form)
                       (with-meta (concat (cons (first form) (next form))
                                          (list x))
                         (meta form))
                       (list form x))]
        (recur threaded (next forms)))
      x)))

(def syms '(= < <= >= + +' - -' * /
              aget alength apply assoc assoc-in
              bit-and-not bit-set bit-shift-left bit-shift-right bit-xor boolean boolean?
              booleans boolean-array bound? butlast
              cat char char? conj cons contains? count cycle
              dec dec' decimal? dedupe dissoc distinct disj double drop
              eduction empty? even? every?
              get get-in
              first float? floats fnil
              hash hash-map
              identity inc inc' int-array interleave into iterate
              juxt
              filter filterv find format frequencies
              last line-seq long
              keep keep-indexed keys
              map map? map-indexed mapv mapcat max max-key meta merge merge-with min munge
              name newline nfirst not= num
              neg? nth nthrest
              odd?
              peek pos?

              re-seq re-find re-pattern remove rest repeatedly reverse
              rand-int rand-nth range reduce reduced? reversible?

              set? sequential? some? str
              set/difference set/join
              str/join str/starts-with? str/ends-with? str/split
              take take-last take-nth take-while tree-seq type
              second set seq seq? seque short shuffle simple-symbol? sort sort-by subs
              transduce

              update update-in
              unchecked-inc-int unchecked-long unchecked-negate unchecked-remainder-int
              unchecked-subtract-int unsigned-bit-shift-right unchecked-float unchecked-add-int
              vals vary-meta vec vector?
              zipmap zero?))

;; TODO:
#_(def all-syms
  '#{when-first while if-not when-let if-some biginteger let quot ns-aliases read unchecked-double key longs not= string? uri? aset-double unchecked-multiply-int chunk-rest pcalls *allow-unresolved-vars* remove-all-methods ns-resolve as-> aset-boolean trampoline double? when-not *1 vec *print-meta* when int map-entry? ns-refers rand second vector-of hash-combine > replace int? associative? unchecked-int set-error-handler! inst-ms* keyword? force bound-fn* namespace-munge group-by prn extend unchecked-multiply some->> default-data-readers ->VecSeq even? unchecked-dec Inst tagged-literal? double-array in-ns create-ns re-matcher defn ref bigint extends? promise aset-char rseq ex-cause construct-proxy agent-errors *compile-files* ex-message *math-context* float pr-str concat aset-short set-agent-send-off-executor! ns symbol to-array-2d mod amap pop use VecNode unquote declare dissoc! reductions aset-byte indexed? ref-history-count - assoc! hash-set reduce-kv or cast reset! name ffirst sorted-set counted? byte-array IVecImpl tagged-literal println extend-type macroexpand-1 assoc-in char-name-string bit-test defmethod requiring-resolve EMPTY-NODE time memoize alter-meta! future? zero? simple-keyword? require unchecked-dec-int persistent! nnext add-watch not-every? class? rem agent-error some future-cancelled? memfn neg-int? struct-map drop *data-readers* nth sorted? nil? extend-protocol split-at *e load-reader random-sample cond-> dotimes select-keys bit-and bounded-count update list* reify update-in prefer-method aset-int *clojure-version* ensure-reduced *' instance? with-open mix-collection-hash re-find run! val defonce unchecked-add loaded-libs ->Vec bytes? not with-meta unreduced the-ns record? type identical? unchecked-divide-int ns-name max-key *unchecked-math* defn- *out* file-seq agent ns-map set-validator! ident? defprotocol swap! vals unchecked-subtract tap> *warn-on-reflection* sorted-set-by sync qualified-ident? assert *compile-path* true? release-pending-sends print empty remove-method *in* print-ctor letfn volatile! / read-line reader-conditional? bit-or clear-agent-errors vector proxy-super >= drop-last not-empty distinct partition loop add-classpath bit-flip long-array descendants merge accessor integer? mapv partition-all partition-by numerator object-array with-out-str condp derive load-string special-symbol? ancestors subseq error-handler gensym cond ratio? delay? intern print-simple flatten doubles halt-when with-in-str remove-watch ex-info ifn? some-> nat-int? proxy-name ns-interns all-ns find-protocol-method subvec for binding partial chunked-seq? find-keyword replicate min-key reduced char-escape-string re-matches array-map unchecked-byte with-local-vars ns-imports send-off defmacro every-pred keys rationalize load-file distinct? pos-int? extenders unchecked-short methods odd? ->ArrayChunk float-array *3 alias frequencies read-string proxy rsubseq inc get-method with-redefs uuid? bit-clear filter locking list + split-with aset ->VecNode keyword *ns* destructure *assert* defmulti chars str next hash-map if-let underive ref-max-history Throwable->map false? *print-readably* ints class some-fn case *flush-on-newline* to-array bigdec list? simple-ident? bit-not io! xml-seq VecSeq byte max == *agent* lazy-cat comment parents count supers *fn-loader* ArrayChunk sorted-map-by apply interpose deref assoc rational? transient clojure-version chunk-cons comparator sorted-map send drop-while proxy-call-with-super realized? char-array resolve compare complement *compiler-options* *print-dup* defrecord with-redefs-fn sequence constantly get-proxy-class make-array shorts completing update-proxy unchecked-negate-int hash-unordered-coll repeat unchecked-inc nthnext and create-struct get-validator number? await-for chunk-next print-str not-any? into-array qualified-symbol? init-proxy chunk-buffer seqable? symbol? when-some unchecked-char ->> future-cancel var-get commute coll? get-in fnext denominator bytes gen-and-load-class refer-clojure})

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
    (map? expr)
    (let [i #(interpret % in)]
      (zipmap (map i (keys expr))
              (map i (vals expr))))
    (seq? expr)
    (if-let [f (first expr)]
      (if-let [v (var-lookup f)]
        (apply-fn v in (rest expr))
        (cond
          (or (= 'if f) (= 'when f))
          (let [[_if cond then else] expr]
            (if (interpret cond in)
              (interpret then in)
              (interpret else in)))
          (= '-> f)
          (interpret (expand-> (rest expr)) in)
          (= '->> f)
          (interpret (expand->> (rest expr)) in)
          ;; bb/fn passed as higher order fn, still needs input
          (-> f meta ::fn)
          (apply-fn (f in) in (rest expr))
          (ifn? f)
          (apply-fn f in (rest expr))
          :else nil))
      expr)
    ;; bb/fn passed as higher order fn, still needs input
    (-> expr meta ::fn)
    (expr in)
    :else expr))

(defn read-fn [form]
  ^::fn
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
