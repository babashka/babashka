(ns orchard.misc
  ;; These will be added in clojure 1.11:
  (:refer-clojure :exclude [update-keys update-vals pmap])
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [orchard.util.io :as util.io])
  (:import
   (java.util.concurrent.locks ReentrantLock)))

(defn os-windows? []
  (.startsWith (System/getProperty "os.name") "Windows"))

(defn url?
  "Check whether the argument is an url"
  [u]
  (instance? java.net.URL u))

(defn directory?
  "Whether the argument is a directory or an url that points to a directory"
  [f]
  (if (url? f)
    (and (util.io/direct-url-to-file? f)
         (.isDirectory (io/as-file f)))
    (.isDirectory (io/as-file f))))

(defn file-ext?
  "Whether the argument's path ends in one of the specified case-insensitive
  file extensions"
  [f & exts]
  (when-let [file (if (url? f)
                    (when (util.io/direct-url-to-file? f)
                      (io/as-file f))
                    (io/as-file f))]
    (and
     ;; Check for file-ness because having a specific extension implies we assume `f` is a file:
     (not (directory? f))
     (some (fn [ext]
             (.endsWith (.. file getName toLowerCase) ext))
           exts))))

(defn clj-file?  [f] (file-ext? f ".clj" ".cljc"))
(defn java-file? [f] (file-ext? f ".java"))
(defn jar-file?  [f] (file-ext? f ".jar"))
(defn archive?   [f] (file-ext? f ".jar" ".zip"))

(defn as-sym
  [x]
  (cond
    (symbol? x) x
    (string? x) (if-let [[_ ns sym] (re-matches #"(.+)/(.+)" x)]
                  (symbol ns sym)
                  (symbol x))))

(defn namespace-sym
  "Return the namespace of a fully qualified symbol if possible, as a symbol.

  It leaves the symbol untouched if not."
  [sym]
  (if-let [ns (and sym (namespace sym))]
    (as-sym ns)
    sym))

(defn name-sym
  "Return the name of a fully qualified symbol if possible, as a symbol.

  It leaves the symbol untouched if not."
  [sym]
  (if-let [n (and sym (name sym))]
    (as-sym n)
    sym))

(defmacro with-lock
  "Like `clojure.core/locking`, but for java.util.concurrent.locks.Lock."
  [lock & body]
  `(let [^ReentrantLock l# ~lock]
     (.lock l#)
     (try ~@body
          (finally (.unlock l#)))))

(defn update-vals
  "Update the values of map `m` via the function `f`."
  [f m]
  (reduce-kv (fn [acc k v]
               (assoc acc k (f v)))
             {} m))

(defn update-keys
  "Update the keys of map `m` via the function `f`."
  [f m]
  (reduce-kv (fn [acc k v]
               (assoc acc (f k) v))
             {} m))

(defn deep-merge
  "Merge maps recursively. When vals are not maps, last value wins."
  [& xs]
  (let [f (fn f [& xs]
            (if (every? map? xs)
              (apply merge-with f xs)
              (last xs)))]
    (apply f (filter identity xs))))

(defn assoc-some
  "Assoc key-value to the map `m` if `v` is non-nil."
  [m k v]
  (if (nil? v) m (assoc m k v)))

(defn parse-java-version
  "Parse a Java version string according to JEP 223 and return the appropriate
  version."
  [java-ver]
  (try (let [[major minor _] (str/split java-ver #"\.")
             major (Integer/parseInt major)]
         (if (> major 1)
           major
           (Integer/parseInt minor)))
       (catch Exception _ 8)))

(def java-api-version
  (parse-java-version (System/getProperty "java.specification.version")))

;; TODO move back to analysis.cljs
(defn add-ns-macros
  "Append $macros to the input symbol"
  [sym]
  (some-> sym
          (str "$macros")
          symbol))

;; TODO move back to analysis.cljs
(defn remove-macros
  "Remove $macros from the input symbol"
  [sym]
  (some-> sym
          str
          (str/replace #"\$macros" "")
          symbol))

(defn ns-obj?
  "Return true if n is a namespace object"
  [ns]
  (instance? clojure.lang.Namespace ns))

(defn require-and-resolve
  "Like `clojure.core/requiring-resolve`, but doesn't throw if the namespace
  was not found or failed to load. Returns the var's value, not the var itself."
  {:added "0.5"}
  [sym]
  (try (some-> sym requiring-resolve var-get)
       (catch Exception _)))

(defn call-when-resolved
  "Return a fn that calls the fn resolved through `var-sym` with the
  arguments passed to it. `var-sym` will be required and resolved
  once. If requiring failed or the `var-sym` can't be resolved the
  function always returns nil."
  [var-sym]
  (let [resolved-fn (require-and-resolve var-sym)]
    (fn [& args]
      (when resolved-fn
        (apply resolved-fn args)))))

(defn- into! [transient-coll1 transient-coll2]
  (reduce conj! transient-coll1 (persistent! transient-coll2)))

(defn pmap
  "Like `clojure.core/pmap`, but uses parallel streams for better efficiency."
  [f, ^java.util.Collection coll]
  (-> (.parallelStream coll)
      (.map (reify java.util.function.Function
              (apply [_ x] (f x))))
      (.collect (reify java.util.function.Supplier
                  (get [_] (volatile! (transient []))))
                (reify java.util.function.BiConsumer
                  (accept [_ acc x] (vswap! acc conj! x)))
                (reify java.util.function.BiConsumer
                  (accept [_ acc1 acc2]
                    (vswap! acc1 into! @acc2))))
      deref
      persistent!))
