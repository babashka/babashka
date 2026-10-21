(ns orchard.print
  "Custom object printer used by development tooling like Inspector. Similar to
  Clojure's `print-method`. Main objectives:

  - honor `*print-level*` and `*print-length*` variables
  - provide sufficiently good performance
  - limit the maximum print size and stop printing after it is reached"
  {:author "Oleksandr Yakushev"
   :added "0.24"}
  (:refer-clojure :exclude [print print-str])
  (:require [clojure.string :as str])
  (:import
   (clojure.core Eduction)
   #_(clojure.lang AFunction Compiler IDeref IPending IPersistentMap MultiFn
                 IPersistentSet IPersistentVector IRecord Keyword Namespace
                 RT Symbol TaggedLiteral Var) ;; BB-PATCH no Compiler in the image; sci's vars and namespaces are its own types
(clojure.lang AFunction IDeref IPending IPersistentMap MultiFn
                 IPersistentSet IPersistentVector IRecord Keyword
                 RT Symbol TaggedLiteral)
   (sci.lang Namespace Var)
   (java.io Writer)
   (java.util Iterator List Map Map$Entry)
   (java.util.concurrent ConcurrentHashMap)
   (mx.cider.orchard TruncatingStringWriter
                     TruncatingStringWriter$TotalLimitExceeded)))

(defrecord ^:private RecordProbe []) ;; BB-PATCH every sci record is a SciRecord

(def ^:private structural-print-interfaces
  "The generic collection interfaces that orchard prints structurally on its
  own.  A value whose `print-method` resolves to one of their implementations
  has no custom representation (see `custom-print-method?`)."
  #_[clojure.lang.IPersistentMap clojure.lang.IPersistentSet
   clojure.lang.IPersistentVector clojure.lang.IRecord clojure.lang.ISeq
   java.util.List java.util.Map java.util.RandomAccess java.util.Set] ;; BB-PATCH a sci record prints through SciRecord's print-method, not IRecord's
  [clojure.lang.IPersistentMap clojure.lang.IPersistentSet
   clojure.lang.IPersistentVector clojure.lang.IRecord clojure.lang.ISeq
   java.util.List java.util.Map java.util.RandomAccess java.util.Set
   (class (->RecordProbe))])

(deftype PrintMethodCache [table prefers structural ^ConcurrentHashMap classes])

(def ^:private print-method-cache
  "Caches per class whether it has a custom `print-method` (see
  `custom-print-method?`).  The whole cache is dropped whenever `print-method`
  gains or loses implementations or preferences, so re-registering a method -
  e.g. when reloading a namespace - can't leave stale results behind."
  (atom nil))

(defn- current-print-method-cache
  "Return the up-to-date cache, rebuilding it if `print-method`'s method or
  preference tables have changed since it was built."
  ^PrintMethodCache []
  (let [table (methods print-method)
        prefs (prefers print-method)
        ^PrintMethodCache cache @print-method-cache]
    (if (and cache
             (identical? table (.-table cache))
             (identical? prefs (.-prefers cache)))
      cache
      ;; A thread caching into a cache that is being replaced is harmless: it
      ;; writes into an object that no one will look at again.
      (reset! print-method-cache
              (PrintMethodCache. table prefs
                                 (into #{}
                                       (keep #(get-method print-method %))
                                       structural-print-interfaces)
                                 (ConcurrentHashMap.))))))

(defn- custom-print-method?
  "True if `x` has a `print-method` more specific than the generic collection
  implementations - i.e. a deliberate custom textual representation that orchard
  should use instead of traversing the value's structure.  This keeps records
  and collections that define their own `print-method` (e.g. `tech.ml.dataset`
  datasets) rendered as intended, and avoids descending into - and potentially
  looping on - such objects' internals."
  [x]
  (let [c (class x)
        cache (current-print-method-cache)
        ^ConcurrentHashMap classes (.-classes cache)
        cached (.get classes c)]
    (if (some? cached)
      cached
      (let [v (if-let [m (try (get-method print-method c)
                              ;; get-method throws when several implementations
                              ;; match `c` and none is preferred.  Print such
                              ;; values structurally.
                              (catch Exception _ nil))]
                (not (contains? (.-structural cache) m))
                false)]
        (.put classes c v)
        v))))

(defn- structural-tag
  "Return the `print` dispatch value for the collection types that orchard
  prints structurally, nil for other types.  Clojure maps implement
  java.util.Map, so the Map clause covers them too.  Don't add an
  IPersistentMap clause here: the :map printer requires java.util.Map, so
  IPersistentMap-only types must keep falling through to :default."
  [x]
  (cond (instance? IRecord x)           :record
        (instance? Map x)               :map
        (instance? IPersistentVector x) :vector
        (instance? List x)              :list
        (instance? IPersistentSet x)    :set))

(defmulti print
  (fn [x _]
    (cond
      (nil? x)                        nil
      ;; Allow meta :type override regular types.
      (:type (meta x))                (type x)
      (instance? String x)            :string
      (instance? Double x)            :double
      (instance? Number x)            :scalar
      (instance? Symbol x)            :scalar
      (instance? Keyword x)           :keyword
      ;; Records and collections may define a custom `print-method`; prefer it
      ;; over structural printing so their intended representation is kept.
      (structural-tag x)              (if (custom-print-method? x)
                                        :custom
                                        (structural-tag x))
      (instance? Eduction x)          :list
      (instance? Var x)               :default
      (.isArray (class x))            :array
      :else                           (type x))))

(def ^:dynamic *max-atom-length*
  "Maximum length of the string written to the Writer in one call."
  Integer/MAX_VALUE)

(def ^:dynamic *max-total-length*
  "Maximum total size of the resulting string."
  Integer/MAX_VALUE)

(def ^:dynamic *coll-show-only-diff*
  "When displaying collection diffs, whether to hide matching values."
  false)

(def ^:dynamic *pov-ns*
  "The \"point-of-view namespace\" for the printer. When bound to a namespace
  object, use this namespace data to shorten qualified keywords:
  - print `::foo` instead of `:pov.ns/foo`
  - print `::alias/foo` instead of `:ns.aliases.in.pov.ns/foo`"
  nil)

(def ^:dynamic *short-record-names*
  "When true, only simple record classnames will be displayed instead of FQNs."
  true)

(defn- print-coll-item
  "Print an item in the context of a collection. When printing a map, don't print
  `[]` characters around map entries."
  [^Writer w, x, map?]
  (if (and map? (instance? Map$Entry x))
    (do (print (.getKey ^Map$Entry x) w)
        (.write w " ")
        (print (.getValue ^Map$Entry x) w))
    (print x w)))

(defn- print-coll
  ([w x sep prefix suffix]
   (print-coll w x sep prefix suffix false))
  ([^Writer w, ^Iterable x, ^String sep, ^String prefix,
    ^String suffix, map?]
   (let [level *print-level*]
     (when-not (nil? level)
       (set! *print-level* (dec level)))
     (try
       (let [^Iterator it (try (RT/iter (if (instance? Iterable x) x (seq x)))
                               ;; In some cases, calling .iterator may throw
                               ;; (e.g. incomplete CollReduce implementations).
                               (catch Exception ex
                                 (RT/iter [(format "<<%s>>" ex)])))]
         (if (.hasNext it)
           (do (.write w prefix)
               (if (or (nil? level) (pos? level))
                 (do (print-coll-item w (.next it) map?)
                     (loop [remaining (unchecked-dec
                                       (long (or *print-length* Long/MAX_VALUE)))]
                       (when (.hasNext it)
                         (.write w sep)
                         (if (> remaining 0)
                           (do (print-coll-item w (.next it) map?)
                               (recur (unchecked-dec remaining)))
                           ;; There are more items but we reached the limit.
                           (.write w "...")))))
                 ;; Special case: ran out of nesting levels.
                 (.write w "..."))
               (.write w suffix))
           ;; Special case: collection has zero elements.
           (do (.write w prefix)
               (.write w suffix))))
       (finally (when-not (nil? level)
                  (set! *print-level* level)))))))

(defmethod print nil [_ ^Writer w]
  (.write w "nil"))

(defmethod print :string [^String x, ^Writer w]
  (let [len (.length x)
        max-len *max-atom-length*
        truncate? (and max-len (< max-len len))
        len (if max-len (min max-len len) len)]
    (.append w \")
    (dotimes [n len]
      (let [c (.charAt x n)
            e (char-escape-string c)]
        (if e (.write w e) (.append w c))))
    (when truncate?
      (.write w "..."))
    (.append w \")))

(defmethod print :scalar [^Object x, ^Writer w]
  (.write w (str x)))

(defmethod print :keyword [^Keyword kw, ^Writer w]
  (if-some [kw-ns (and *pov-ns* (namespace kw))]
    (if (= kw-ns (name (ns-name *pov-ns*)))
      (do (.write w "::")
          (.write w (name kw)))
      (if-some [matched-alias (some (fn [[alias ns]]
                                      (when (= kw-ns (name (ns-name ns)))
                                        alias))
                                    (ns-aliases *pov-ns*))]
        (do (.write w "::")
            (.write w (name matched-alias))
            (.write w "/")
            (.write w (name kw)))
        (.write w (str kw))))
    (.write w (str kw))))

(defmethod print :double [x, ^Writer w]
  (cond (= Double/POSITIVE_INFINITY x) (.write w "##Inf")
        (= Double/NEGATIVE_INFINITY x) (.write w "##-Inf")
        (Double/isNaN x) (.write w "##NaN")
        :else (.write w (str x))))

(defmethod print :persistent-map [x w]
  (print-coll w x ", " "{" "}" true))

(defmethod print :vector [x w]
  (print-coll w x " " "[" "]"))

(defmethod print :list [x w]
  (print-coll w x " " "(" ")"))

(defmethod print :set [x w]
  (print-coll w x " " "#{" "}"))

(defn- print-map [^Map x, ^Writer w]
  (if (.isEmpty x)
    (.write w "{}")
    (let [;; If the map is a Clojure map, don't take the entrySet but iterate
          ;; directly as the order might be important.
          coll (if (instance? IPersistentMap x) x (.entrySet ^Map x))]
      (print-coll w coll ", " "{" "}" true))))

(defmethod print :map [^Map x, w]
  (print-map x w))

#_(defmethod print :record [x, ^Writer w]
  (.write w "#")
  (.write w (if *short-record-names*
              (.getSimpleName (class x))
              (.getName (class x))))
  (print-map x w)) ;; BB-PATCH every sci record is a SciRecord, its name is on the sci type
(defmethod print :record [x, ^Writer w]
  (.write w "#")
  (.write w (let [full-name (.getName (type x))]
              (if *short-record-names*
                (subs full-name (inc (.lastIndexOf full-name ".")))
                full-name)))
  (print-map x w))

(defn- print-structurally
  "Print `x` with orchard's own structural printer even if its type has a
  custom `print-method`."
  [x, ^Writer w]
  ((get-method print (structural-tag x)) x w))

(defmethod print :custom [x, ^Writer w]
  ;; The value's type defines its own representation - honor it. But a custom
  ;; print-method is arbitrary user code: if it throws, or overflows the stack
  ;; on a self-referential value (#412), fall back to structural printing.
  ;; TotalLimitExceeded extends Error, so it propagates through both catches.
  (try (print-method x w)
       (catch StackOverflowError _ (print-structurally x w))
       (catch Exception _ (print-structurally x w))))

(defmethod print :array [x, ^Writer w]
  (let [ct (.getName (or (.getComponentType (class x)) Object))
        as-seq (seq x)]
    (.write w ct)
    (if as-seq
      (print-coll w as-seq ", " "[] {" "}")
      (.write w "[] {}"))))

(defmethod print IDeref [^IDeref x, ^Writer w]
  (let [pending (and (instance? IPending x)
                     (not (realized? x)))
        [ex val]
        (when-not pending
          (try [false (deref x)]
               (catch Throwable e
                 [true e])))
        full-name (.getName (class x))
        name (cond (str/starts-with? full-name "clojure.core$future_call") "future"
                   (str/starts-with? full-name "clojure.core$promise") "promise"
                   :else (str/lower-case (.getSimpleName (class x))))
        err (or (when ex val)
                (when (instance? clojure.lang.Agent x) (agent-error x)))]
    (.write w "#")
    (.write w name)
    (print (cond err ['<failed> err]
                 pending '[<pending>]
                 :else [val])
           w)))

(defmethod print Class [x w]
  (print-method x w))

(defmethod print AFunction [x, ^Writer w]
  (.write w "#function[")
  (.write w #_(Compiler/demunge (.getName (class x))) ;; BB-PATCH no Compiler in the image
(clojure.main/demunge (.getName (class x))))
  (.write w "]"))

(def ^:private multifn-name-field
  (delay (doto (.getDeclaredField MultiFn "name")
           (.setAccessible true))))

(defn- multifn-name [^MultiFn mfn]
  (try (.get ^java.lang.reflect.Field @multifn-name-field mfn)
       (catch SecurityException _ "_")))

(defmethod print MultiFn [x, ^Writer w]
  ;; MultiFn names are not unique so we keep the identity to ensure it's unique.
  (.write w (format "#multifn[%s 0x%x]"
                    (multifn-name x) (System/identityHashCode x))))

(defmethod print TaggedLiteral [x w]
  (print-method x w))

(defmethod print Namespace [x, ^Writer w]
  (.write w "#namespace[")
  (.write w (str (ns-name x)))
  ;; MultiFn names are not unique so we keep the identity to ensure it's unique.
  (.write w "]"))

(defmethod print Throwable [^Throwable x, ^Writer w]
  (.write w "#error[")
  (.write w (str (.getName (class x)) " "))
  (loop [cause x, msg nil]
    (if cause
      (recur (.getCause cause) (str msg (when msg ": ") (.getMessage cause)))
      (print msg w)))
  (when-let [data (not-empty (ex-data x))]
    (.write w " ")
    (print data w))
  (when-let [first-frame (first (.getStackTrace x))]
    (.write w " ")
    (print (str first-frame) w))
  (.write w "]"))

;;;; Diffing support. Used for orchard.inspect/diff.

(deftype Diff [d1 d2])
(deftype DiffColl [coll]) ;; For collections that contain diff elements.
(deftype Nothing []) ;; To represent absent value.
(def nothing (->Nothing))

(defn diff-result?
  "Return true if the object represents a diff result."
  [x]
  (or (instance? Diff x) (instance? DiffColl x)))

(defn diff-coll-hide-equal-items [coll]
  (cond (map? coll) (into {} (filter (fn [[_ v]] (diff-result? v))
                                     coll))
        (sequential? coll) (mapv #(if (diff-result? %) % nothing)
                                 coll)
        (set? coll) (set (filter diff-result? coll))
        :else coll))

(defmethod print DiffColl [^DiffColl x, ^Writer w]
  (let [coll (cond-> (.coll x)
               *coll-show-only-diff* diff-coll-hide-equal-items)]
    (.write w "#≠")
    (print coll w)))

(defmethod print Diff [^Diff x, ^Writer w]
  (let [d1 (.d1 x), d2 (.d2 x)]
    (.write w "#±[")
    (print d1 w)
    (.write w " ~~ ")
    (print d2 w)
    (.write w "]")))

(defmethod print Nothing [_ _])

(defmethod print :default [^Object x, ^Writer w]
  (print-method x w))

(defn print-str
  "Alternative implementation of `clojure.core/pr-str` which supports truncating
  intermediate items and the resulting string and short-circuiting when the
  limit is reached."
  [x]
  (let [writer (TruncatingStringWriter. *max-atom-length* *max-total-length*)]
    ;; Protect against self-referencing collections by ensuring
    ;; print-level is always set to at least an unreasonably large value.
    (binding [*print-level* (or *print-level* 100)]
      (try (print x writer)
           (catch TruncatingStringWriter$TotalLimitExceeded _)))
    (.toString writer)))
