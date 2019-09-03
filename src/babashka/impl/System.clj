(ns babashka.impl.System)

(defn get-env
  ([] (System/getenv))
  ([s] (System/getenv s)))

(defn get-property
  ([s]
   (System/getProperty s))
  ([s d]
   (System/getProperty s d)))

(defn set-property [k v]
  (System/setProperty k v))

(defn get-properties []
  (System/getProperties))

(defn exit [n]
  (throw (ex-info "" {:bb/exit-code n})))

(def system-bindings
  {'System/getenv get-env
   'System/getProperty get-property
   'System/setProperty set-property
   'System/getProperties get-properties
   'System/exit exit})
