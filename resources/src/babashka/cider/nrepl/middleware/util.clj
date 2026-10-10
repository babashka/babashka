(ns cider.nrepl.middleware.util
  "Utility functions that might be useful in middleware."
  (:require [nrepl.transport :as transport]
            [nrepl.misc :refer [response-for]]
            [orchard.misc :as misc]))

(defmulti transform-value "Transform a value for output" type)

(defmethod transform-value :default [v] (str v))

(defmethod transform-value Number
  [v]
  ;; bencode supports only integers, so we convert
  ;; other numbers (e.g. floats) to string
  (if (integer? v)
    v
    (str v)))

(defmethod transform-value nil [_v] nil)

(defmethod transform-value java.io.File
  [v]
  (.getAbsolutePath ^java.io.File v))

(defmethod transform-value clojure.lang.Sequential
  [v]
  (list* (map transform-value v)))

(defmethod transform-value clojure.lang.Symbol
  [v]
  (let [[the-ns the-name] [(namespace v) (name v)]]
    (if the-ns
      (str the-ns "/" the-name)
      the-name)))

(defmethod transform-value clojure.lang.Keyword
  [v]
  (transform-value (.sym ^clojure.lang.Keyword v)))

(defmethod transform-value clojure.lang.Associative
  [m]
  (->> (for [[k v] m] ; bencode keys must be strings
         [(str (transform-value k)) (transform-value v)])
       (into {})))

;; handles vectors
(prefer-method transform-value clojure.lang.Sequential clojure.lang.Associative)

(defn respond-to
  "Send a response for `msg` with `response-data` using message's transport."
  [msg & response-data]
  (transport/send (:transport msg) (apply response-for msg response-data)))

(defn msg->var
  "Resolve the var referred to by the `:ns` and `:sym` slots of an op `msg`,
  coercing both from their on-the-wire strings. Returns nil if it doesn't
  resolve to a var."
  [{:keys [ns sym]}]
  (ns-resolve (misc/as-sym ns) (misc/as-sym sym)))
