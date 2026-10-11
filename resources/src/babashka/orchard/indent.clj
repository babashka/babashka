(ns orchard.indent
  "`:style/indent` inference."
  (:require
   [clojure.set :as set]
   [clojure.string :as str])
  (:import
   (java.util List)))

(defn- index-by-name [m]
  (into {}
        (map (fn [[k v]]
               [(-> k name) [k v]]))
        m))

(def clojure-mode-indents-exact
  "`:style/indent` rules based on clojure-mode, for names that are short
  and might be a syllable/word within a larger, unrelated word/phrase.

  e.g. `are` can be part of `compare`, so `are` should only be looked for exact matches,
  not for fuzzy matches.

  Uses the modern tuple format shared with clojure-mode and clojure-ts-mode:
  [[:block N]] — N special args, then body
  [[:inner D]] — body-style at nesting depth D
  [[:inner D I]] — body-style at depth D, only at position I"
  (index-by-name '{clojure.core/->           nil
                   clojure.core/->>          nil
                   clojure.test/are          [[:block 2]]
                   clojure.core/binding      [[:block 1]]
                   clojure.core/case         [[:block 1]]
                   catch                     [[:block 2]]
                   clojure.core/comment      [[:block 0]]
                   clojure.core/cond         [[:block 0]]
                   def                       [[:inner 0]]
                   clojure.core/defn         [[:inner 0]]
                   clojure.core/delay        [[:block 0]]
                   do                        [[:block 0]]
                   clojure.core/extend       [[:block 1]]
                   clojure.spec.alpha/fdef   [[:block 1]]
                   clojure.core/fn           [[:inner 0]]
                   clojure.core/for          [[:block 1]]
                   clojure.core.async/go     [[:block 0]]
                   if                        [[:block 1]]
                   clojure.core/let          [[:block 1]]
                   clojure.core/locking      [[:block 1]]
                   clojure.core/loop         [[:block 1]]
                   clojure.core/ns           [[:block 1]]
                   clojure.core/proxy        [[:block 2] [:inner 1]]
                   clojure.core/reify        [[:inner 0] [:inner 1]]
                   clojure.test/testing      [[:block 1]]
                   this-as                   [[:block 1]]
                   clojure.core.async/thread [[:block 0]]
                   try                       [[:block 0]]
                   clojure.core/when         [[:block 1]]
                   clojure.core/while        [[:block 1]]}))

(def clojure-mode-indents-fuzzy
  "`:style/indent` rules based on clojure-mode, for names that are deemed long/unique enough.
  See `clojure-mode-indents-exact` for format documentation."
  (index-by-name '{clojure.core.async/alt!      [[:block 0]]
                   clojure.core.async/alt!!     [[:block 0]]
                   clojure.core/as->            [[:block 2]]
                   clojure.core/bound-fn        [[:inner 0]]
                   clojure.core/cond->          [[:block 1]]
                   clojure.core/cond->>         [[:block 1]]
                   clojure.core/condp           [[:block 2]]
                   clojure.core/definterface    [[:block 1] [:inner 1]]
                   clojure.core/defmethod       [[:inner 0]]
                   clojure.core/defprotocol     [[:block 1] [:inner 1]]
                   clojure.core/defrecord       [[:block 2] [:inner 1]]
                   clojure.test/deftest         [[:inner 0]]
                   clojure.core/deftype         [[:block 2] [:inner 1]]
                   clojure.core/doseq           [[:block 1]]
                   clojure.core/dotimes         [[:block 1]]
                   clojure.core/doto            [[:block 1]]
                   clojure.core/extend-protocol [[:block 1] [:inner 0]]
                   clojure.core/extend-type     [[:block 1] [:inner 0]]
                   finally                      [[:block 0]]
                   clojure.core/future          [[:block 0]]
                   clojure.core.async/go-loop   [[:block 1]]
                   clojure.core/if-let          [[:block 1]]
                   clojure.core/if-not          [[:block 1]]
                   clojure.core/if-some         [[:block 1]]
                   clojure.core/letfn           [[:block 1] [:inner 2 0]]
                   clojure.core/some->          nil
                   clojure.core/some->>         nil
                   clojure.test/use-fixtures    [[:inner 0]]
                   clojure.core/when-first      [[:block 1]]
                   clojure.core/when-let        [[:block 1]]
                   clojure.core/when-not        [[:block 1]]
                   clojure.core/when-some       [[:block 1]]}))

(defn- symbolize-arglist [arglist]
  (mapv #(if (= % '&) % '_) arglist))

(defn- structure= [candidate reference]
  (set/subset? (into #{} (map symbolize-arglist) candidate)
               (into #{} (map symbolize-arglist) reference)))

(defn- acceptably-similar? [candidate-arglists var-symbol]
  (or (nil? (namespace var-symbol))
      (let [resolved (resolve var-symbol)]
        (or (nil? resolved)
            (structure= candidate-arglists (:arglists (meta resolved)))))))

(defn- find-idx [^List coll element]
  (let [idx (some-> coll (.indexOf element))]
    (when (and idx (> idx -1)) idx)))

(defn- compute-style-indent [^String macro-name [^List arglist :as arglists]]
  (let [[exact-sym exact-indentation] (clojure-mode-indents-exact macro-name)
        [fuzzy-sym fuzzy-indentation] (or
                                       ;; An exact match is preferable
                                       (clojure-mode-indents-fuzzy macro-name)
                                       (some (fn [[k v]]
                                               (when (str/includes? macro-name k)
                                                 v))
                                             clojure-mode-indents-fuzzy))
        one-arglist? (= (count arglists) 1)
        &-idx (find-idx arglist '&)
        then-idx (find-idx arglist 'then)
        else-idx (find-idx arglist 'else)]
    (cond
      (and exact-sym (acceptably-similar? arglists exact-sym))
      exact-indentation

      (and fuzzy-sym (acceptably-similar? arglists fuzzy-sym))
      fuzzy-indentation

      ;; def-like macros get no inference - these tend to be risky to make any assumption about
      (str/starts-with? macro-name "def")
      nil

      (and one-arglist? &-idx)
      &-idx

      (and one-arglist? then-idx else-idx (> else-idx then-idx)
           (= else-idx (dec (count arglist))))
      then-idx

      one-arglist?
      (some #(find-idx arglist %)
            ['body
             'forms
             'clauses
             'args
             'body-expr
             'fntail
             'fn-tail]))))

(defn- normalize-arglists
  "Unwrap a `(quote ...)` wrapper around `arglists`, if present.

  ClojureScript's analyzer preserves `:arglists` as the raw form when it
  comes from a macro-written macro (i.e. a macro whose body emits
  `'([x])` for `:arglists` instead of a bare `([x])`).  Most consumers
  expect the bare seq of arglist vectors, so peel the `quote` off before
  processing."
  [arglists]
  (if (and (seq? arglists)
           (= 'quote (first arglists)))
    (second arglists)
    arglists))

(defn infer-style-indent
  "Given a `metadata` map obtained from a Clojure var object,
  associates a `:style/indent` value based on inference (rule of thumb) rules:

  * The macro name is inspected in conjunction with the arglist,
    in search for an acceptably analog match with a clojure.core counterpart.
  * If that fails, the position of `&` is observed.
  * Otherwise, the argument names are observed.

  The exact inference logic is an implementation detail.

  `:style/indent` will be not associated if no rule matched."
  [{:keys [name arglists] :as metadata}]
  (let [result (compute-style-indent (str name) (normalize-arglists arglists))]
    (cond-> metadata
      result (assoc :style/indent result))))
