(ns babashka.impl.proxy
  {:no-doc true}
  (:require [sci.impl.types]))

(set! *warn-on-reflection* false)

(defn method-or-bust [methods k]
  (or (get methods k)
      (throw (UnsupportedOperationException. "Method not implemented: " k))))

(defn proxy-fn [{:keys [:class :methods]}]
  (case (.getName ^Class class)
    "clojure.lang.APersistentMap"
    (proxy [clojure.lang.APersistentMap] []
      (seq [] ((method-or-bust methods 'seq)))
      (valAt
        ([k] ((method-or-bust methods 'valAt) k))
        ([k default] ((method-or-bust methods 'valAt) k default))))))
