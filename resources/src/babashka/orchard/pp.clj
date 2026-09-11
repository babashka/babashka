(ns orchard.pp
  "A pretty-printer for Clojure data structures. This namespace is borrowed from
  Eero Helenius' pp project and modified according to Orchard needs. Linear
  printing parts were replaced with substitutes from `orchard.print` for reuse
  and consistency.

  Based on the algorithm described in \"Pretty-Printing, Converting List
  to Linear Structure\" by Ira Goldstein (Artificial Intelligence, Memo
  No. 279 in Massachusetts Institute of Technology A.I. Laboratory,
  February 1973)."
  {:author "Eero Helenius"
   :license "MIT"
   :git/url "https://github.com/eerohele/pp.git"}
  (:require [clojure.string :as str]
            [orchard.print :as print])
  (:import (mx.cider.orchard TruncatingStringWriter
                             TruncatingStringWriter$TotalLimitExceeded)
           (orchard.print DiffColl)))

(defn ^:private strip-ns
  "Given a (presumably qualified) ident, return an unqualified version
  of the ident."
  [x]
  (cond
    (keyword? x) (keyword nil (name x))
    (symbol? x) (symbol nil (name x))))

(defn ^:private extract-map-ns
  "Given a map, iff the keys in the map are qualified idents that share
  a namespace, return a tuple where the first item is the namespace
  name (a string) and the second item is a copy of the original map
  but with unqualified idents."
  [m]
  (when (seq m)
    (loop [m m ns nil nm {}]
      (if-some [[k v] (first m)]
        (when (qualified-ident? k)
          (let [k-ns (namespace k)]
            (when (or (nil? ns) (= ns k-ns))
              (recur (rest m) k-ns (assoc nm (strip-ns k) v)))))
        [ns nm]))))

(defmacro ^:private array?
  [x]
  `(some-> ~x class .isArray))

(defn ^:private open-delim
  "Return the opening delimiter (a string) of coll."
  ^String [coll]
  (cond
    (map? coll) "{"
    (vector? coll) "["
    (set? coll) "#{"
    (array? coll) (let [ct (.getName (or (.getComponentType (class coll))
                                         Object))]
                    (str ct "[] {"))
    :else "("))

(defn ^:private close-delim
  "Return the closing delimiter (a string) of coll."
  ^String [coll]
  (cond
    (map? coll) "}"
    (vector? coll) "]"
    (set? coll) "}"
    (array? coll) "}"
    :else ")"))

(defprotocol ^:private CountKeepingWriter
  (^:private write [this s]
    "Write a string into the underlying java.io.Writer while keeping
    count of the length of the strings written into the writer.")

  (^:private remaining [this]
    "Return the number of characters available on the current line.")

  (^:private nl [this]
    "Write a newline into the underlying java.io.Writer.

    Resets the number of characters allotted to the current line to
    zero."))

(defn ^:private strlen
  "Given a string, return the length of the string.

  Since java.lang.String isn't counted?, (.length s) is faster than (count s)."
  ^long [s]
  (.length ^String s))

(defn ^:private count-keeping-writer
  "Given a java.io.Writer and an options map, wrap the java.io.Writer
  such that it becomes a CountKeepingWriter: a writer that keeps count
  of the length of the strings written into each line. Options:
  - :max-width (long) - maximum line width."
  [^java.io.Writer writer opts]
  (let [max-width (:max-width opts)
        c (volatile! 0)]
    (reify CountKeepingWriter
      (write [_ s]
        (.write writer ^String s)
        (vswap! c (fn [^long n] (unchecked-add-int n (strlen ^String s))))
        nil)
      (remaining [_]
        (unchecked-subtract-int max-width @c))
      (nl [_]
        (.write writer "\n")
        (vreset! c 0)
        nil))))

(def ^:private reader-macros
  {'quote "'"
   'var "#'"
   'clojure.core/deref "@",
   'clojure.core/unquote "~"})

(defn ^:private open-delim+form
  "Given a coll, return a tuple where the first item is the coll's
  opening delimiter and the second item is the coll.

  If *print-namespace-maps* is true, the coll is a map, and the map is
  amenable to the map namespace syntax, the open delimiter includes
  the map namespace prefix and the map keys are unqualified.

  If the coll is a record, the open delimiter includes the record name
  prefix."
  [coll]
  (if (record? coll)
    [(str "#" (if print/*short-record-names*
                (.getSimpleName (class coll))
                (.getName (class coll))) "{") coll]
    ;; If all keys in the map share a namespace and *print-
    ;; namespace-maps* is true, print the map using map namespace
    ;; syntax (e.g. #:a{:b 1} instead of {:a/b 1}). If the map is
    ;; a record, print the map using the record syntax (e.g.
    ;; #user.R{:x 1}).
    (let [[ns ns-map]
          (when (and *print-namespace-maps* (map? coll))
            (extract-map-ns coll))

          coll (if ns ns-map coll)

          o (if ns (str "#:" ns "{") (open-delim coll))]
      [o coll])))

(defn ^:private meets-print-level?
  "Given a level (a long), return true if the level is the same as
  *print-level*."
  [level]
  (and (int? *print-level*) (= level *print-level*)))

(defn- print-str-linear
  "Print an object in linear style (without regard to line length) and return as a
  string. Options:
  - :level (long) - the current nesting level."
  ^String [x opts]
  (binding [*print-level* (when *print-level*
                            (- *print-level* (:level opts 0)))]
    (print/print-str x)))

(defn ^:private print-mode
  "Given a CountKeepingWriter, a form, and an options map, return a keyword
  indicating a printing mode (:linear or :miser)."
  [writer form opts]
  (let [reserve-chars (:reserve-chars opts)
        s (print-str-linear form opts)]
    ;; If, after (possibly) reserving space for any closing delimiters of
    ;; ancestor S-expressions, there's enough space to print the entire
    ;; form in linear style on this line, do so.
    ;;
    ;; Otherwise, print the form in miser style.
    (if (<= (strlen s) (unchecked-subtract-int (remaining writer) reserve-chars))
      :linear
      :miser)))

(defn ^:private write-sep
  "Given a CountKeepingWriter and a printing mode, print a separator (a
  space or a newline) into the writer."
  [writer mode]
  (case mode
    :miser (nl writer)
    (write writer " ")))

(defprotocol ^:private PrettyPrintable
  (^:private -pprint [this writer opts]
    "Given a form, a CountKeepingWriter, and options, pretty-print the form into
  the writer.
  Options:
  - :level (long) - the current nesting level. For example, in [[:a 1]], the outer
    vector is nested at level 0, and the inner vector is nested at level 1.
  - :indentation (String) - a string (of spaces) to use for indentation.
  - :reserve-chars (long) - number of characters reserved for closing delimiters of
    S-expressions above the current nesting level."))

(defn ^:private pprint-meta
  [form writer opts mode]
  (when (and *print-meta* *print-readably*)
    (when-some [m (meta form)]
      (when (seq m)
        (write writer "^")
        ;; As per https://github.com/clojure/clojure/blob/6975553804b0f8da9e196e6fb97838ea4e153564/src/clj/clojure/core_print.clj#L78-L80
        (let [m (if (and (= (count m) 1) (:tag m)) (:tag m) m)]
          (-pprint m writer opts))
        (write-sep writer mode)))))

(defn ^:private pprint-opts
  [open-delim opts]
  (let [;; The indentation level is the indentation level of the
        ;; parent S-expression plus a number of spaces equal to the
        ;; length of the open delimiter (e.g. one for "(", two for
        ;; "#{").
        padding (apply str (repeat (strlen open-delim) " "))
        indentation (str (:indentation opts) padding)]
    (-> opts (assoc :indentation indentation) (update :level inc))))

(defn ^:private -pprint-coll
  "Like -pprint, but only for lists, vectors and sets."
  [this writer opts]
  (if (meets-print-level? (:level opts))
    (write writer "#")
    (let [[^String o form] (open-delim+form this)
          mode (print-mode writer this opts)
          opts (pprint-opts o opts)]

      ;; Print possible meta
      (pprint-meta form writer opts mode)

      ;; Print open delimiter
      (write writer o)

      ;; Print S-expression content
      (if (= *print-length* 0)
        (write writer "...")
        (when (seq form)
          (loop [form form index 0]
            (if (= index *print-length*)
              (do
                (when (= mode :miser) (write writer (:indentation opts)))
                (write writer "..."))

              (do
                ;; In miser mode, prepend indentation to every form
                ;; except the first one. We don't want to prepend
                ;; indentation for the first form, because it
                ;; immediately follows the open delimiter.
                (when (and (= mode :miser) (pos? index))
                  (write writer (:indentation opts)))

                (let [f (first form)
                      n (next form)]
                  (if (empty? n)
                    ;; This is the last child, so reserve an additional
                    ;; slot for the closing delimiter of the parent
                    ;; S-expression.
                    (-pprint f writer (update opts :reserve-chars inc))
                    (do
                      (-pprint f writer (assoc opts :reserve-chars 0))
                      (write-sep writer mode)
                      (recur n (inc index))))))))))

      ;; Print close delimiter
      (write writer (close-delim form)))))

(defn ^:private -pprint-map-entry
  "Pretty-print a map entry within a map."
  [this writer opts]
  (if (meets-print-level? (:level opts))
    (write writer "#")
    (let [k (key this)
          opts (update opts :level inc)]
      (-pprint k writer opts)

      (let [v (val this)
            ;; If, after writing the map entry key, there's enough space to
            ;; write the val on the same line, do so. Otherwise, write
            ;; indentation followed by val on the following line.
            mode (print-mode writer v (update opts :reserve-chars inc))]
        (write-sep writer mode)
        (when (= :miser mode) (write writer (:indentation opts)))
        (-pprint v writer opts)))))

(defn ^:private -pprint-map
  "Like -pprint, but only for maps."
  [this writer opts]
  (if (meets-print-level? (:level opts))
    (write writer "#")
    (let [[^String o form] (open-delim+form this)
          mode (print-mode writer this opts)
          opts (pprint-opts o opts)]
      (pprint-meta form writer opts mode)
      (write writer o)
      (if (= *print-length* 0)
        (write writer "...")
        (when (seq form)
          (loop [form form index 0]
            (if (= index *print-length*)
              (do
                (when (= mode :miser) (write writer (:indentation opts)))
                (write writer "..."))

              (do
                (when (and (= mode :miser) (pos? index))
                  (write writer (:indentation opts)))

                (let [f (first form)
                      n (next form)]
                  (if (empty? n)
                    (-pprint-map-entry f writer (update opts :reserve-chars inc))
                    (let [^String map-entry-separator (:map-entry-separator opts)]
                      ;; Reserve a slot for the map entry separator.
                      (-pprint-map-entry f writer (assoc opts :reserve-chars (strlen map-entry-separator)))
                      (write writer map-entry-separator)
                      (write-sep writer mode)
                      (recur n (inc index))))))))))

      (write writer (close-delim form)))))

(defn ^:private -pprint-seq
  [this writer opts]
  (if-some [reader-macro (reader-macros (first this))]
    (if (meets-print-level? (:level opts))
      (write writer "#")
      (do
        (write writer reader-macro)
        (-pprint (second this) writer
                 (update opts :indentation str " "))))
    (-pprint-coll this writer opts)))

(defn ^:private -pprint-diff-coll
  [^DiffColl this writer opts]
  (if (meets-print-level? (:level opts))
    (write writer "#")
    (let [coll (cond-> (.coll this)
                 print/*coll-show-only-diff* print/diff-coll-hide-equal-items)]
      (write writer "#≠")
      (-pprint coll writer (update opts :indentation str "  ")))))

(extend-protocol PrettyPrintable
  nil
  (-pprint [_ writer _]
    (write writer "nil"))

  clojure.lang.AMapEntry
  (-pprint [this writer opts]
    (-pprint-coll this writer opts))

  clojure.lang.ISeq
  (-pprint [this writer opts]
    (-pprint-seq this writer opts))

  clojure.lang.IPersistentMap
  (-pprint [this writer opts]
    (-pprint-map this writer opts))

  clojure.lang.IPersistentVector
  (-pprint [this writer opts]
    (-pprint-coll this writer opts))

  clojure.lang.IPersistentSet
  (-pprint [this writer opts]
    (-pprint-coll this writer opts))

  clojure.lang.PersistentQueue
  (-pprint [this writer opts]
    (-pprint-coll (or (seq this) ()) writer opts))

  DiffColl
  (-pprint [this writer opts]
    (-pprint-diff-coll this writer opts))

  Object
  (-pprint [this writer opts]
    (if (array? this)
      (-pprint-seq this writer opts)
      (write writer (print-str-linear this opts)))))

(defn pprint
  "Pretty-print an object into `writer` (*out* by default). Options:
  - `:indentation` (string) - Shift printed value by this string to the right.
  - `:max-width` (default: 72) - Avoid printing anything beyond the column
  indicated by this value."
  ([x] (pprint *out* x nil))
  ([x opts] (pprint *out* x opts))
  ([^java.io.Writer writer x
    {:keys [indentation max-width] :or {indentation "", max-width 72} :as opts}]
   (let [writer' (count-keeping-writer writer {:max-width max-width})]
     (-pprint x writer' (assoc opts
                               :map-entry-separator ","
                               :level 0
                               :indentation indentation
                               :max-width max-width
                               :reserve-chars 0))
     (nl writer'))
   (when *flush-on-newline*
     (.flush ^java.io.Writer writer))))

(defn pprint-str
  "Pretty print the object `x`. The `:indentation` option is the number of spaces
  used for indentation."
  ([x] (pprint-str x {}))
  ([x options]
   (let [{:keys [indentation] :or {indentation 0}} options
         writer (TruncatingStringWriter. print/*max-atom-length*
                                         print/*max-total-length*)
         indentation-str (apply str (repeat indentation " "))]
     (try (pprint writer x {:indentation indentation-str
                            :max-width (+ indentation 80)})
          (catch TruncatingStringWriter$TotalLimitExceeded _))
     (str/trimr (.toString writer)))))
