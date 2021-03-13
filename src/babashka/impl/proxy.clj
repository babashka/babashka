(ns babashka.impl.proxy
  {:no-doc true}
  (:require [sci.impl.types]))

(set! *warn-on-reflection* false)

(defn method-or-bust [methods k]
  (or (get methods k)
      (throw (UnsupportedOperationException. "Method not implemented: " k))))

(defn method-or [methods k default]
  (or (get methods k)
      default))

(defn proxy-fn [{:keys [class methods]}]
  (case (.getName ^Class class)
    "clojure.lang.APersistentMap"
    (proxy [clojure.lang.APersistentMap clojure.lang.IMeta clojure.lang.IObj] []
      (iterator [] ((method-or-bust methods 'iterator) this))
      (containsKey [k] ((method-or-bust methods 'containsKey) this k))
      (entryAt [k] ((method-or-bust methods 'entryAt) this k))
      (valAt
        ([k] ((method-or-bust methods 'valAt) this k))
        ([k default] ((method-or-bust methods 'valAt) this k default)))
      (cons [v]
        (if-let [m (get methods 'cons)]
          (m this v)
          (proxy-super cons v)))
      (count [] ((method-or-bust methods 'count) this))
      (assoc [k v] ((method-or-bust methods 'assoc) this k v))
      (without [k] ((method-or-bust methods 'without) this k))
      (seq [] ((method-or-bust methods 'seq) this))

      (equiv [other]
        (if-let [m (get methods 'equiv)]
          (m this other)
          (proxy-super equiv other)))
      (empty [] ((method-or-bust methods 'empty) this))

      (meta [] ((method-or methods 'meta nil) this))
      (withMeta [meta] ((method-or methods 'withMeta this) this meta))

      (toString []
        (if-let [m (get methods 'toString)]
          (m this)
          (proxy-super toString))))
    "clojure.lang.AMapEntry"
    (proxy [clojure.lang.AMapEntry] []
      (key [] ((method-or-bust methods 'key) this))
      (val [] ((method-or-bust methods 'val) this))
      (getKey [] ((method-or-bust methods 'getKey) this))
      (getValue [] ((method-or-bust methods 'getValue) this)))))
