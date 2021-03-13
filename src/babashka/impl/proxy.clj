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
      (iterator [] ((method-or-bust methods 'iterator)))
      (containsKey [k] ((method-or-bust methods 'containsKey) k))
      (entryAt [k] ((method-or-bust methods 'entryAt) k))
      (valAt
        ([k] ((method-or-bust methods 'valAt) k))
        ([k default] ((method-or-bust methods 'valAt) k default)))
      (cons [v] ((method-or-bust methods 'cons) v))
      (count [] ((method-or-bust methods 'count)))
      (assoc [k v] ((method-or-bust methods 'assoc) k v))
      (without [k] ((method-or-bust methods 'without) k))
      (seq [] ((method-or-bust methods 'seq))))
    "clojure.lang.AMapEntry"
    (proxy [clojure.lang.AMapEntry] []
      (key [] ((method-or-bust methods 'key)))
      (val [] ((method-or-bust methods 'val))))))
