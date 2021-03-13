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
      (iterator [] ((method-or-bust methods 'iterator) this))
      (containsKey [k] ((method-or-bust methods 'containsKey) this k))
      (entryAt [k] ((method-or-bust methods 'entryAt) this k))
      (valAt
        ([k] ((method-or-bust methods 'valAt) this k))
        ([k default] ((method-or-bust methods 'valAt) this k default)))
      (cons [v] ((method-or-bust methods 'cons) this v))
      (count [] ((method-or-bust methods 'count) this))
      (assoc [k v] ((method-or-bust methods 'assoc) this k v))
      (without [k] ((method-or-bust methods 'without) this k))
      (seq [] ((method-or-bust methods 'seq) this)))
    "clojure.lang.AMapEntry"
    (proxy [clojure.lang.AMapEntry] []
      (key [] ((method-or-bust methods 'key) this))
      (val [] ((method-or-bust methods 'val) this)))))
