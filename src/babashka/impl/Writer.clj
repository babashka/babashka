(ns babashka.impl.Writer
  {:no-doc true}
  (:refer-clojure :exclude [flush]))

(defn write
  ([^java.io.Writer w ^String x]
   (.write w x))
  ([^java.io.Writer w ^String x ^long off ^long len]
   (.write w x off len)))

(defn append
  ([^java.io.Writer w ^String x]
   (.append w x))
  ([^java.io.Writer w ^String x ^long off ^long len]
   (.append w x off len)))

(defn close [^java.io.Writer w]
  (.close w))

(defn flush [^java.io.Writer w]
  (.flush w))

(def writer-bindings
  {'.append append
   '.write write
   '.close close
   '.flush flush})
