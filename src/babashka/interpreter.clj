(ns babashka.interpreter
  {:no-doc true}
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

(declare interpret)

(defn eval-and
  "The and macro from clojure.core."
  [in args]
  (if (empty? args) true
      (let [[x & xs] args
            v (interpret x in)]
        (if v
          (if (empty? xs) v
              (eval-and in xs))
          v))))

(defn eval-or
  "The or macro from clojure.core."
  [in args]
  (if (empty? args) nil
      (let [[x & xs] args
            v (interpret x in)]
        (if v v
            (if (empty? xs) v
                (eval-or in xs))))))

(def syms '(= < <= > >= + +' - -' * *' / == aget alength apply assoc assoc-in
              associative? array-map

              bit-and-not bit-set bit-shift-left bit-shift-right bit-xor boolean
              boolean?  booleans boolean-array bound? butlast byte-array bytes
              bigint bit-test bit-and bounded-count bytes? bit-or bit-flip
              biginteger bigdec bit-not byte

              cat char char? conj cons contains? count cycle comp concat
              comparator coll? compare complement char-array constantly
              char-escape-string chars completing counted? chunk-rest
              char-name-string class chunk-next

              dec dec' decimal? dedupe dissoc distinct distinct? disj double
              double? drop drop-last drop-while denominator doubles

              eduction empty empty? even? every? every-pred ensure-reduced

              first float? floats fnil fnext ffirst flatten false? filter
              filterv find format frequencies float float-array

              get get-in group-by gensym

              hash hash-map hash-set hash-unordered-coll

              ident? identical? identity inc inc' int-array interleave into
              iterate int int? interpose indexed? integer? ints into-array

              juxt

              keep keep-indexed key keys keyword keyword?

              last line-seq long list list? longs list* long-array

              map map? map-indexed map-entry? mapv mapcat max max-key meta merge
              merge-with min min-key munge mod make-array

              name newline nfirst not not= not-every? num neg? neg-int? nth
              nthnext nthrest nil? nat-int? number? not-empty not-any? next
              nnext namespace-munge numerator

              odd? object-array

              peek pop pos? pos-int? partial partition partition-all
              partition-by

              qualified-ident? qualified-symbol? qualified-keyword? quot

              re-seq re-find re-pattern re-matches rem remove rest repeatedly
              reverse rand-int rand-nth range reduce reduce-kv reduced reduced?
              reversible? replicate rsubseq reductions rational? rand replace
              rseq ratio? rationalize random-sample repeat

              set? sequential? select-keys simple-keyword? simple-symbol? some?
              string? str

              set/difference set/index set/intersection set/join set/map-invert
              set/project set/rename set/rename-keys set/select set/subset?
              set/superset? set/union

              str/blank? str/capitalize str/ends-with? str/escape str/includes?
              str/index-of str/join str/last-index-of str/lower-case
              str/re-quote-replacement str/replace str/replace-first str/reverse
              str/split str/split-lines str/starts-with? str/trim
              str/trim-newline str/triml str/trimr str/upper-case

              second set seq seq? seque short shuffle
              sort sort-by subs symbol symbol? special-symbol? subvec some-fn
              some split-at split-with sorted-set subseq sorted-set-by
              sorted-map-by sorted-map sorted? simple-ident? sequence seqable?
              shorts

              take take-last take-nth take-while transduce tree-seq type true?
              to-array

              update update-in uri? uuid? unchecked-inc-int unchecked-long
              unchecked-negate unchecked-remainder-int unchecked-subtract-int
              unsigned-bit-shift-right unchecked-float unchecked-add-int
              unchecked-double unchecked-multiply-int unchecked-int
              unchecked-multiply unchecked-dec-int unchecked-add unreduced
              unchecked-divide-int unchecked-subtract unchecked-negate-int
              unchecked-inc unchecked-char unchecked-byte unchecked-short

              val vals vary-meta vec vector vector?

              xml-seq

              zipmap zero?))

;; TODO:
#_(def all-syms
  '#{when-first while if-not when-let if-some let as-> when-not some->>  or cond-> loop  condp cond  some-> if-let case and when-some  })

(declare var-lookup apply-fn)

(defmacro define-lookup []
  `(defn ~'var-lookup [sym#]
     (case sym#
       ~@(for [s# syms
               s# [s# s#]]
           s#)
       nil)))

(define-lookup)

(defmacro one-of [x elements]
  `(let [x# ~x]
     (case x# (~@elements) x# nil)))

(defn resolve-symbol [expr]
  (let [n (name expr)]
    (if (str/starts-with? n "'")
      (symbol (subs n 1))
      (or (var-lookup expr)
          (throw (Exception. (format "Could not resolve symbol: %s." n)))))))

(defn interpret
  [expr in]
  (let [i #(interpret % in)]
    (cond
      (= '*in* expr) in
      (symbol? expr) (resolve-symbol expr)
      (map? expr)
      (zipmap (map i (keys expr))
              (map i (vals expr)))
      (or (vector? expr) (set? expr))
      (into (empty expr) (map i expr))
      (seq? expr)
      (if-let [f (first expr)]
        (let [f (or (one-of f [if when and or -> ->>])
                    (interpret f in))]
          (if-let [v (var-lookup f)]
            (apply-fn v i (rest expr))
            (case f
              (if when)
              (let [[_if cond then else] expr]
                (if (interpret cond in)
                  (interpret then in)
                  (interpret else in)))
              ->
              (interpret (expand-> (rest expr)) in)
              ->>
              (interpret (expand->> (rest expr)) in)
              and
              (eval-and in (rest expr))
              or
              (eval-or in (rest expr))
              ;; fallback
              ;; read fn passed as higher order fn, still needs input
              (cond (-> f meta ::fn)
                    (apply-fn (f in) i (rest expr))
                    (symbol? f)
                    (apply-fn (resolve-symbol f) i (rest expr))
                    (ifn? f)
                    (apply-fn f i (rest expr))
                    :else (throw (Exception. (format "Cannot call %s as a function." (pr-str f))))))))
        expr)
      ;; read fn passed as higher order fn, still needs input
      (-> expr meta ::fn)
      (expr in)
      :else expr)))

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

(defn apply-fn [f i args]
  (let [args (mapv i args)]
    (apply f args)))

;;;; Scratch

(comment
  (interpret '(and *in* 3) 1)
  (interpret '(and *in* 3 false) 1)
  (interpret '(or *in* 3) nil)
  (ifn? 'foo)
  )
