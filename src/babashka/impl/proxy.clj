(ns babashka.impl.proxy
  {:no-doc true}
  (:require [sci.impl.types]))

(set! *warn-on-reflection* false)

(defn method-or-bust [methods k]
  (or (get methods k)
      (throw (UnsupportedOperationException. (str "Method not implemented: " k)))))

(defn class-name [^Class clazz]
  (.getName clazz))

(defn proxy-fn [{:keys [class interfaces protocols methods args]}]
  (let [interface-names (set (map class-name interfaces))]
    (case [(class-name class) interface-names]
      ;; This combination is used by pathom3
      ["clojure.lang.APersistentMap" #{"clojure.lang.IMeta" "clojure.lang.IObj"}]
      (proxy [clojure.lang.APersistentMap clojure.lang.IMeta clojure.lang.IObj sci.impl.types.ICustomType] []
        (getInterfaces []
          interfaces)
        (getMethods []
          methods)
        (getProtocols []
          protocols)
        (getFields []
          nil)
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

      ["sun.misc.SignalHandler" #{}]
      (proxy [sun.misc.SignalHandler] []
        (handle [sig]
          ((method-or-bust methods 'handle) this sig)))

      ["java.io.InputStream" #{}]
      (proxy [java.io.InputStream] []
        (available [] ((method-or-bust methods 'available) this))
        (close [] ((method-or-bust methods 'close) this))
        (read
          ([]
           ((method-or-bust methods 'read) this))
          ([bs]
           ((method-or-bust methods 'read) this bs))
          ([bs off len]
           ((method-or-bust methods 'read) this bs off len))))

      ["java.io.PipedInputStream" #{}]
      (proxy [java.io.PipedInputStream] []
        (available [] ((method-or-bust methods 'available) this))
        (close [] ((method-or-bust methods 'close) this))
        (read
          ([]
           ((method-or-bust methods 'read) this))
          ([bs]
           ((method-or-bust methods 'read) this bs))
          ([bs off len]
           ((method-or-bust methods 'read) this bs off len)))
        (receive [b] ((method-or-bust methods 'receive) this b)))

      ["java.io.PipedOutputStream" #{}]
      (proxy [java.io.PipedOutputStream] []
        (close [] ((method-or-bust methods 'close) this))
        (connect [snk] ((method-or-bust methods 'connect) this snk))
        (flush [] ((method-or-bust methods 'flush) this))
        (write
          ([b] ((method-or-bust methods 'write) this b))
          ([b off len] ((method-or-bust methods 'write) this b off len))))

      ["java.io.OutputStream" #{}]
      (proxy [java.io.OutputStream] []
        (close [] (when-let [m (get methods 'close)]
                    (m this)))
        (flush [] (when-let [m (get methods 'flush)]
                    (m this)))
        (write
          ([b]
           ((method-or-bust methods 'write) this b))
          ([b off len]
           ((method-or-bust methods 'write) this b off len))))
      ["javax.net.ssl.X509ExtendedTrustManager" #{}]
      (proxy [javax.net.ssl.X509ExtendedTrustManager] []
        (checkClientTrusted
          ([x y]
           ((method-or-bust methods 'checkClientTrusted) this x y))
          ([x y z]
           ((method-or-bust methods 'checkClientTrusted) this x y z)))
        (checkServerTrusted
          ([x y]
           ((method-or-bust methods 'checkServerTrusted) this x y))
          ([x y z]
           ((method-or-bust methods 'checkServerTrusted) this x y z)))
        (getAcceptedIssuers [] ((method-or-bust methods 'getAcceptedIssuers) this)))

      ["java.lang.ThreadLocal" #{}]
      (proxy [java.lang.ThreadLocal] []
        (initialValue []
          ((method-or-bust methods 'initialValue) this)))

      ["org.jline.reader.Completer" #{}]
      (proxy [org.jline.reader.Completer sci.impl.types.ICustomType] []
        (getInterfaces [] interfaces)
        (getMethods [] methods)
        (getProtocols [] protocols)
        (getFields [] nil)
        (complete [reader line candidates]
          ((method-or-bust methods 'complete) this reader line candidates)))

      ["org.jline.reader.Highlighter" #{}]
      (proxy [org.jline.reader.Highlighter sci.impl.types.ICustomType] []
        (getInterfaces [] interfaces)
        (getMethods [] methods)
        (getProtocols [] protocols)
        (getFields [] nil)
        (highlight [reader buffer]
          ((method-or-bust methods 'highlight) this reader buffer)))

      ["org.jline.reader.ParsedLine"]
      (proxy [org.jline.reader.ParsedLine clojure.lang.IMeta sci.impl.types.ICustomType] []
        (getInterfaces [] interfaces)
        (getMethods [] methods)
        (getProtocols [] protocols)
        (getFields [] nil)
        (word [] ((method-or-bust methods 'word) this))
        (wordIndex [] ((method-or-bust methods 'wordIndex) this))
        (wordCursor [] ((method-or-bust methods 'wordCursor) this))
        (words [] ((method-or-bust methods 'words) this))
        (line [] ((method-or-bust methods 'line) this))
        (cursor [] ((method-or-bust methods 'cursor) this)))

      ["java.io.Writer" #{}]
      (proxy [java.io.Writer sci.impl.types.ICustomType] []
        (getInterfaces [] interfaces)
        (getMethods [] methods)
        (getProtocols [] protocols)
        (getFields [] nil)
        (flush [] ((method-or-bust methods 'flush) this))
        (close [] ((method-or-bust methods 'close) this))
        (write
          ([str-cbuf off len]
           ((method-or-bust methods 'write) this str-cbuf off len))))

      ["java.io.Reader" #{}]
      (proxy [java.io.Reader sci.impl.types.ICustomType] []
        (getInterfaces [] interfaces)
        (getMethods [] methods)
        (getProtocols [] protocols)
        (getFields [] nil)
        (read
          ([] ((method-or-bust methods 'read) this))
          ([out-array] ((method-or-bust methods 'read) this out-array))
          ([out-array off len] ((method-or-bust methods 'read) this out-array off len)))
        (close [] (when-let [m (get methods 'close)] (m this))))

      ["java.lang.Object" #{}]
      (proxy [java.lang.Object] []
        (equals [obj]
          (if-let [m (get methods 'equals)]
            (m this obj)
            (proxy-super equals obj)))
        (hashCode []
          (if-let [m (get methods 'hashCode)]
            (m this)
            (proxy-super hashCode)))
        (toString []
          (if-let [m (get methods 'toString)]
            (m this)
            (proxy-super toString))))
      , ;; keep this for merge friendliness
      )))

(defn class-sym [c] (symbol (class-name c)))

;; Proxy classes must be registered here so SCI resolves method calls on
;; `this` against the proxy class rather than falling through to public-class.
;; Without this, proxy-super and interface method calls on `this` inside
;; proxy bodies fail.
(def custom-reflect-map
  {(class-sym (get-proxy-class java.net.Authenticator))
   {:methods [{:name "getPasswordAuthentication"}]}
   (class-sym (get-proxy-class java.net.ProxySelector))
   {:methods [{:name "connectFailed"}
              {:name "select"}]}
   (class-sym (get-proxy-class javax.net.ssl.HostnameVerifier))
   {:methods [{:name "verify"}]}
   (class-sym (get-proxy-class java.lang.ThreadLocal))
   {:methods [{:name "get"}]}
   (class-sym (get-proxy-class java.lang.Object))
   {:methods [{:name "equals"}
              {:name "hashCode"}
              {:name "toString"}]}
   (class-sym (get-proxy-class clojure.lang.APersistentMap clojure.lang.IMeta clojure.lang.IObj sci.impl.types.ICustomType))
   {:allPublicMethods true}})

;;; Scratch

(comment

  )
