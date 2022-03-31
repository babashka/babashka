(ns babashka.impl.proxy
  {:no-doc true}
  (:require [sci.impl.types]))

(set! *warn-on-reflection* false)

(defn method-or-bust [methods k]
  (or (get methods k)
      (throw (UnsupportedOperationException. (str "Method not implemented: " k)))))

(defn class-name [^Class clazz]
  (.getName clazz))

(defn proxy-fn [{:keys [class interfaces protocols methods]}]
  (let [interface-names (set (map class-name interfaces))]
    (case [(class-name class) interface-names]
      ;; This combination is used by pathom3
      ["clojure.lang.APersistentMap" #{"clojure.lang.IMeta" "clojure.lang.IObj"}]
      (proxy [clojure.lang.APersistentMap clojure.lang.IMeta clojure.lang.IObj sci.impl.types.IReified] []
        (getInterfaces []
          interfaces)
        (getMethods []
          methods)
        (getProtocols []
          protocols)
        (iterator [] ((method-or-bust methods 'iterator) this))
        (containsKey [k] ((method-or-bust methods 'containsKey) this k))
        (entryAt [k] ((method-or-bust methods 'entryAt) this k))
        (valAt
          ([k]
           ((method-or-bust methods 'valAt) this k))
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

        (meta [] ((method-or-bust methods 'meta) this))
        (withMeta [meta] ((method-or-bust methods 'withMeta) this meta))

        (toString []
          (if-let [m (get methods 'toString)]
            (m this)
            (proxy-super toString))))
      ["clojure.lang.AMapEntry" #{}]
      (proxy [clojure.lang.AMapEntry] []
        (key [] ((method-or-bust methods 'key) this))
        (val [] ((method-or-bust methods 'val) this))
        (getKey [] ((method-or-bust methods 'getKey) this))
        (getValue [] ((method-or-bust methods 'getValue) this)))

      ["java.net.Authenticator" #{}]
      (proxy [java.net.Authenticator] []
        (getPasswordAuthentication []
          ((method-or-bust methods 'getPasswordAuthentication) this)))

      ["java.net.ProxySelector" #{}]
      (proxy [java.net.ProxySelector] []
        (connectFailed [uri socket-address ex]
          ((method-or-bust methods 'connectFailed) this uri socket-address ex))
        (select [uri] ((method-or-bust methods 'select) this uri)))

      ["javax.net.ssl.HostnameVerifier" #{}]
      (proxy [javax.net.ssl.HostnameVerifier] []
        (verify [host-name session] ((method-or-bust methods 'verify) this host-name session)))

      ["java.io.PipedInputStream" #{}]
      (proxy [java.io.PipedInputStream] []
        (available [] ((method-or-bust methods 'available) this))
        (close [] ((method-or-bust methods 'close) this))
        (read
          ([]
           ((method-or-bust methods 'read) this))
          ([b off len]
           ((method-or-bust methods 'read) this b off len)))
        (receive [b] ((method-or-bust methods 'receive) this b)))

      ["java.io.PipedOutputStream" #{}]
      (proxy [java.io.PipedOutputStream] []
        (close [] ((method-or-bust methods 'close) this))
        (connect [snk] ((method-or-bust methods 'connect) this snk))
        (flush [] ((method-or-bust methods 'flush) this))
        (write
          ([b] ((method-or-bust methods 'write) this b))
          ([b off len] ((method-or-bust methods 'write) this b off len)))))))

(defn class-sym [c] (symbol (class-name c)))

(def custom-reflect-map
  {(class-sym (class (proxy-fn {:class java.net.Authenticator})))
   {:methods [{:name "getPasswordAuthentication"}]}
   (class-sym (class (proxy-fn {:class java.net.ProxySelector})))
   {:methods [{:name "connectFailed"}
              {:name "select"}]}
   (class-sym (class (proxy-fn {:class javax.net.ssl.HostnameVerifier})))
   {:methods [{:name "verify"}]}})

;;; Scratch

(comment

  )
