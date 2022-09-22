(ns babashka.impl.classes
  {:no-doc true}
  (:require
   [babashka.impl.features :as features]
   [babashka.impl.proxy :as proxy]
   [cheshire.core :as json]
   [clojure.core.async]
   [sci.core :as sci]
   [sci.impl.types :as t]))

(def base-custom-map
  `{clojure.lang.LineNumberingPushbackReader {:allPublicConstructors true
                                              :allPublicMethods true}
    java.lang.Thread
    {:allPublicConstructors true
     ;; generated with `public-declared-method-names`, see in
     ;; `comment` below
     :methods [{:name "activeCount"}
               {:name "checkAccess"}
               {:name "currentThread"}
               {:name "dumpStack"}
               {:name "enumerate"}
               {:name "getAllStackTraces"}
               {:name "getContextClassLoader"}
               {:name "getDefaultUncaughtExceptionHandler"}
               {:name "getId"}
               {:name "getName"}
               {:name "getPriority"}
               {:name "getStackTrace"}
               {:name "getState"}
               {:name "getThreadGroup"}
               {:name "getUncaughtExceptionHandler"}
               {:name "holdsLock"}
               {:name "interrupt"}
               {:name "interrupted"}
               {:name "isAlive"}
               {:name "isDaemon"}
               {:name "isInterrupted"}
               {:name "join"}
               {:name "run"}
               {:name "setContextClassLoader"}
               {:name "setDaemon"}
               {:name "setDefaultUncaughtExceptionHandler"}
               {:name "setName"}
               {:name "setPriority"}
               {:name "setUncaughtExceptionHandler"}
               {:name "sleep"}
               {:name "start"}
               {:name "toString"}
               {:name "yield"}]}
    java.net.URL
    {:allPublicConstructors true
     :allPublicFields true
     ;; generated with `public-declared-method-names`, see in
     ;; `comment` below
     :methods [{:name "equals"}
               {:name "getAuthority"}
               {:name "getContent"}
               {:name "getDefaultPort"}
               {:name "getFile"}
               {:name "getHost"}
               {:name "getPath"}
               {:name "getPort"}
               {:name "getProtocol"}
               {:name "getQuery"}
               {:name "getRef"}
               {:name "getUserInfo"}
               {:name "hashCode"}
               {:name "openConnection"}
               {:name "openStream"}
               {:name "sameFile"}
               ;; not supported: {:name "setURLStreamHandlerFactory"}
               {:name "toExternalForm"}
               {:name "toString"}
               {:name "toURI"}]}
    java.util.Arrays
    {:methods [{:name "copyOf"}
               {:name "copyOfRange"}
               {:name "equals"}]}
    ;; this fixes clojure.lang.Reflector for Java 11
    java.lang.reflect.AccessibleObject
    {:methods [{:name "canAccess"}]}
    java.lang.Package
    {:methods [{:name "getName"}]}
    java.lang.reflect.Member
    {:methods [{:name "getModifiers"}]}
    java.lang.reflect.Method
    {:methods [{:name "getName"}
               {:name "getModifiers"}
               {:name "getParameterTypes"}
               {:name "getReturnType"}]}
    java.lang.reflect.Modifier
    {:methods [{:name "isStatic"}]}
    java.lang.reflect.Field
    {:methods [{:name "getName"}
               {:name "getModifiers"}]}
    java.lang.reflect.Array
    {:methods [{:name "newInstance"}
               {:name "set"}]}
    java.lang.Runnable
    {:methods [{:name "run"}]}
    java.net.Inet4Address
    {:methods [{:name "getHostAddress"}]}
    java.net.Inet6Address
    {:methods [{:name "getHostAddress"}]}
    clojure.lang.IFn
    {:methods [{:name "applyTo"}]}
    clojure.lang.MultiFn
    {:fields [{:name "dispatchFn"}]
     :methods [{:name "getMethod"}]}
    clojure.lang.RT
    {:methods [{:name "aget"}
               {:name "aset"}
               {:name "aclone"}]}
    clojure.lang.Compiler
    {:fields [{:name "specials"}
              {:name "CHAR_MAP"}]}
    clojure.lang.PersistentHashMap
    {:fields [{:name "EMPTY"}]}
    clojure.lang.APersistentVector
    {:methods [{:name "indexOf"}]}
    clojure.lang.ILookup
    {:methods [{:name "valAt"}]}
    clojure.lang.IPersistentMap
    {:methods [{:name "without"}]}
    clojure.lang.IPersistentSet
    {:methods [{:name "disjoin"}]}
    clojure.lang.Indexed
    {:methods [{:name "nth"}]}
    clojure.lang.Ratio
    {:fields [{:name "numerator"}
              {:name "denominator"}]}
    clojure.lang.Agent
    {:fields [{:name "pooledExecutor"}
              {:name "soloExecutor"}]}
    java.util.Iterator
    {:methods [{:name "hasNext"}
               {:name "next"}]}
    java.util.TimeZone
    {:methods [{:name "getTimeZone"}]}})

(def custom-map
  (cond->
      (merge base-custom-map
             proxy/custom-reflect-map)
    features/hsqldb? (assoc `org.hsqldb.dbinfo.DatabaseInformationFull
                            {:methods [{:name "<init>"
                                        :parameterTypes ["org.hsqldb.Database"]}]}
                            `java.util.ResourceBundle
                            {:methods [{:name "getBundle"
                                        :parameterTypes ["java.lang.String","java.util.Locale",
                                                         "java.lang.ClassLoader"]}]})))

(def java-net-http-classes
  "These classes must be initialized at run time since GraalVM 22.1"
  '[java.net.Authenticator
    java.net.CookieHandler
    java.net.CookieManager
    java.net.CookieStore
    java.net.CookiePolicy
    java.net.HttpCookie
    java.net.PasswordAuthentication
    java.net.ProxySelector
    java.net.SocketTimeoutException
    java.net.http.HttpClient
    java.net.http.HttpClient$Builder
    java.net.http.HttpClient$Redirect
    java.net.http.HttpClient$Version
    java.net.http.HttpHeaders
    java.net.http.HttpRequest
    java.net.http.HttpRequest$BodyPublisher
    java.net.http.HttpRequest$BodyPublishers
    java.net.http.HttpRequest$Builder
    java.net.http.HttpResponse
    java.net.http.HttpResponse$BodyHandler
    java.net.http.HttpResponse$BodyHandlers
    java.net.http.HttpTimeoutException
    java.net.http.WebSocket
    java.net.http.WebSocket$Builder
    java.net.http.WebSocket$Listener
    java.security.cert.X509Certificate
    javax.crypto.Mac
    javax.crypto.spec.SecretKeySpec
    javax.net.ssl.HostnameVerifier ;; clj-http-lite
    javax.net.ssl.HttpsURLConnection ;; clj-http-lite
    javax.net.ssl.KeyManagerFactory
    javax.net.ssl.SSLContext
    javax.net.ssl.SSLException
    javax.net.ssl.SSLParameters
    javax.net.ssl.SSLSession ;; clj-http-lite
    javax.net.ssl.TrustManager
    javax.net.ssl.TrustManagerFactory
    javax.net.ssl.X509TrustManager
    jdk.internal.net.http.HttpClientBuilderImpl
    jdk.internal.net.http.HttpClientFacade
    jdk.internal.net.http.HttpRequestBuilderImpl
    jdk.internal.net.http.HttpResponseImpl
    jdk.internal.net.http.common.MinimalFuture
    jdk.internal.net.http.websocket.BuilderImpl
    jdk.internal.net.http.websocket.WebSocketImpl])

(def classes
  `{:all [clojure.lang.ArityException
          clojure.lang.BigInt
          clojure.lang.ExceptionInfo
          java.io.BufferedInputStream
          java.io.BufferedOutputStream
          java.io.BufferedReader
          java.io.BufferedWriter
          java.io.ByteArrayInputStream
          java.io.ByteArrayOutputStream
          java.io.Closeable
          java.io.Console
          java.io.DataInput
          java.io.DataInputStream
          java.io.DataOutput
          java.io.DataOutputStream
          java.io.File
          java.io.FileFilter
          java.io.FilenameFilter
          java.io.FileNotFoundException
          java.io.FileInputStream
          java.io.FileOutputStream
          java.io.FileReader
          java.io.FileWriter
          java.io.RandomAccessFile
          java.io.InputStream
          java.io.IOException
          java.io.OutputStream
          java.io.InputStreamReader
          java.io.OutputStreamWriter
          java.io.PipedInputStream
          java.io.PipedOutputStream
          java.io.PrintStream
          java.io.PrintWriter
          java.io.PushbackInputStream
          java.io.PushbackReader
          java.io.Reader
          java.io.SequenceInputStream
          java.io.StringReader
          java.io.StringWriter
          java.io.Writer
          java.lang.Appendable
          java.lang.ArithmeticException
          java.lang.AssertionError
          java.lang.Boolean
          java.lang.Byte
          java.lang.Character
          java.lang.CharSequence
          java.lang.Class
          java.lang.ClassCastException
          java.lang.ClassNotFoundException
          java.lang.Comparable
          java.lang.Double
          java.lang.Exception
          java.lang.Float
          java.lang.IllegalArgumentException
          java.lang.IllegalStateException
          java.lang.IndexOutOfBoundsException
          java.lang.Integer
          java.lang.InterruptedException
          java.lang.Iterable
          java.lang.Long
          java.lang.NullPointerException
          java.lang.Number
          java.lang.NumberFormatException
          java.lang.Math
          java.lang.Object
          java.lang.Process
          java.lang.ProcessHandle
          java.lang.ProcessHandle$Info
          java.lang.ProcessBuilder
          java.lang.ProcessBuilder$Redirect
          java.lang.Runtime
          java.lang.RuntimeException
          java.lang.Short
          java.lang.StackTraceElement
          java.lang.String
          java.lang.StringBuilder
          java.lang.System
          java.lang.Throwable
          ;; java.lang.UnsupportedOperationException
          java.lang.ref.WeakReference
          java.lang.ref.ReferenceQueue
          java.lang.ref.Cleaner
          java.math.BigDecimal
          java.math.BigInteger
          java.math.MathContext
          java.math.RoundingMode
          java.net.BindException
          java.net.ConnectException
          java.net.DatagramSocket
          java.net.DatagramPacket
          java.net.HttpURLConnection
          java.net.InetAddress
          java.net.InetSocketAddress
          java.net.ServerSocket
          java.net.Socket
          java.net.SocketException
          java.net.UnknownHostException
          java.net.URI
          ;; java.net.URL, see below
          java.net.URLEncoder
          java.net.URLDecoder
          ~@(when features/java-nio?
              '[java.nio.ByteBuffer
                java.nio.ByteOrder
                java.nio.CharBuffer
                java.nio.DirectByteBuffer
                java.nio.DirectByteBufferR
                java.nio.MappedByteBuffer
                java.nio.file.OpenOption
                java.nio.file.StandardOpenOption
                java.nio.channels.FileChannel
                java.nio.channels.FileChannel$MapMode
                java.nio.charset.Charset
                java.nio.charset.CoderResult
                java.nio.charset.CharsetEncoder
                java.nio.charset.StandardCharsets
                java.nio.file.CopyOption
                java.nio.file.DirectoryNotEmptyException
                java.nio.file.FileAlreadyExistsException
                java.nio.file.FileSystem
                java.nio.file.FileSystems
                java.nio.file.FileVisitor
                java.nio.file.FileVisitOption
                java.nio.file.FileVisitResult
                java.nio.file.Files
                java.nio.file.LinkOption
                java.nio.file.NoSuchFileException
                java.nio.file.Path
                java.nio.file.PathMatcher
                java.nio.file.Paths
                java.nio.file.StandardCopyOption
                java.nio.file.attribute.BasicFileAttributes
                java.nio.file.attribute.FileAttribute
                java.nio.file.attribute.FileTime
                java.nio.file.attribute.PosixFilePermission
                java.nio.file.attribute.PosixFilePermissions])
          java.security.MessageDigest
          java.security.DigestInputStream
          java.security.Provider
          java.security.KeyStore
          java.security.SecureRandom
          java.security.Security
          java.sql.Date
          java.text.ParseException
          ;; adds about 200kb, same functionality provided by java.time:
          java.text.SimpleDateFormat
          ~@(when features/java-time?
              `[java.time.format.DateTimeFormatter
                java.time.Clock
                java.time.DateTimeException
                java.time.DayOfWeek
                java.time.Duration
                java.time.Instant
                java.time.LocalDate
                java.time.LocalDateTime
                java.time.LocalTime
                java.time.Month
                java.time.MonthDay
                java.time.OffsetDateTime
                java.time.OffsetTime
                java.time.Period
                java.time.Year
                java.time.YearMonth
                java.time.ZoneRegion
                java.time.ZonedDateTime
                java.time.ZoneId
                java.time.ZoneOffset
                java.time.format.DateTimeFormatterBuilder
                java.time.format.DateTimeParseException
                java.time.format.DecimalStyle
                java.time.format.ResolverStyle
                java.time.format.SignStyle
                java.time.temporal.ChronoField
                java.time.temporal.ChronoUnit
                java.time.temporal.IsoFields
                java.time.temporal.TemporalAdjusters
                java.time.temporal.TemporalAmount
                java.time.temporal.TemporalField
                ~(symbol "[Ljava.time.temporal.TemporalField;")
                java.time.format.TextStyle
                java.time.temporal.Temporal
                java.time.temporal.TemporalAccessor
                java.time.temporal.TemporalAdjuster])
          java.util.concurrent.atomic.AtomicInteger
          java.util.concurrent.atomic.AtomicLong
          java.util.concurrent.atomic.AtomicReference
          java.util.concurrent.CancellationException
          java.util.concurrent.CompletionException
          java.util.concurrent.ExecutionException
          java.util.concurrent.Executor
          java.util.concurrent.LinkedBlockingQueue
          java.util.concurrent.ScheduledThreadPoolExecutor
          java.util.concurrent.ThreadPoolExecutor
          java.util.concurrent.ThreadPoolExecutor$AbortPolicy
          java.util.concurrent.ThreadPoolExecutor$CallerRunsPolicy
          java.util.concurrent.ThreadPoolExecutor$DiscardOldestPolicy
          java.util.concurrent.ThreadPoolExecutor$DiscardPolicy
          java.util.concurrent.ScheduledExecutorService
          java.util.concurrent.Future
          java.util.concurrent.FutureTask
          java.util.concurrent.CompletableFuture
          java.util.concurrent.Executors
          java.util.concurrent.TimeUnit
          java.util.jar.Attributes$Name
          java.util.jar.JarFile
          java.util.jar.JarEntry
          java.util.jar.JarFile$JarFileEntry
          java.util.jar.JarInputStream
          java.util.jar.JarOutputStream
          java.util.jar.Manifest
          java.util.stream.BaseStream
          java.util.stream.Stream
          java.util.Random
          java.util.regex.Matcher
          java.util.regex.Pattern
          java.util.ArrayDeque
          java.util.ArrayList
          java.util.Collections
          java.util.Comparator
          java.util.Base64
          java.util.Base64$Decoder
          java.util.Base64$Encoder
          java.util.Date
          java.util.HashMap
          java.util.IdentityHashMap
          java.util.List
          java.util.Locale
          java.util.Map
          java.util.MissingResourceException
          java.util.NoSuchElementException
          java.util.Optional
          java.util.Properties
          java.util.Scanner
          java.util.Set
          java.util.StringTokenizer
          java.util.WeakHashMap
          java.util.UUID
          java.util.function.Consumer
          java.util.function.Function
          java.util.function.BiConsumer
          java.util.function.BiFunction
          java.util.function.Predicate
          java.util.function.Supplier
          java.util.zip.Inflater
          java.util.zip.InflaterInputStream
          java.util.zip.Deflater
          java.util.zip.DeflaterInputStream
          java.util.zip.DeflaterOutputStream
          java.util.zip.GZIPInputStream
          java.util.zip.GZIPOutputStream
          java.util.zip.ZipInputStream
          java.util.zip.ZipOutputStream
          java.util.zip.ZipEntry
          java.util.zip.ZipException
          java.util.zip.ZipFile
          sun.misc.Signal
          sun.misc.SignalHandler
          ~(symbol "[B")
          ~(symbol "[I")
          ~(symbol "[Ljava.lang.Object;")
          ~(symbol "[Ljava.lang.Double;")
          ~@(when features/datascript?
              `[me.tonsky.persistent_sorted_set.PersistentSortedSet
                datascript.db.DB
                datascript.db.Datom
                ~(symbol "[Lclojure.lang.Keyword;")
                ~(symbol "[Lclojure.lang.PersistentArrayMap;")
                ~(symbol "[Lclojure.lang.PersistentVector;")
                ~(symbol "[Lclojure.lang.PersistentHashSet;")
                ~(symbol "[Ljava.util.regex.Pattern;")
                ~(symbol "[Lclojure.core$range;")])
          ~@(when features/yaml? '[org.yaml.snakeyaml.error.YAMLException])
          ~@(when features/hsqldb? '[org.hsqldb.jdbcDriver])]
    :constructors [clojure.lang.Delay
                   clojure.lang.MapEntry
                   clojure.lang.LineNumberingPushbackReader
                   java.io.EOFException]
    :methods [borkdude.graal.LockFix] ;; support for locking

    :fields [clojure.lang.PersistentQueue
             ~@(when features/postgresql? '[org.postgresql.PGProperty])]
    ;; this just adds the class without any methods also suitable for private
    ;; classes: add the privage class here and the public class to the normal
    ;; list above and then everything reachable via the public class will be
    ;; visible in the native image.
    :instance-checks [clojure.lang.AFn
                      clojure.lang.AFunction
                      clojure.lang.AMapEntry ;; for proxy
                      clojure.lang.APersistentMap ;; for proxy
                      clojure.lang.APersistentSet
                      clojure.lang.AReference
                      clojure.lang.Associative
                      clojure.lang.Atom
                      clojure.lang.Cons
                      clojure.lang.Counted
                      clojure.lang.Cycle
                      clojure.lang.IObj
                      clojure.lang.Fn ;; to distinguish fns from maps, etc.
                      clojure.lang.IPending
                      ;; clojure.lang.IDeref ;; implemented as protocol in sci
                      ;; clojure.lang.IAtom  ;; implemented as protocol in sci
                      clojure.lang.IEditableCollection
                      clojure.lang.IMapEntry
                      clojure.lang.IMeta
                      clojure.lang.IPersistentCollection
                      clojure.lang.IPersistentStack
                      clojure.lang.IPersistentList
                      clojure.lang.IRecord
                      clojure.lang.IReduce
                      clojure.lang.IReduceInit
                      clojure.lang.IKVReduce
                      clojure.lang.IRef
                      clojure.lang.ISeq
                      clojure.lang.IPersistentVector
                      clojure.lang.ITransientVector
                      clojure.lang.Iterate
                      clojure.lang.LazySeq
                      clojure.lang.LispReader$Resolver
                      clojure.lang.Named
                      clojure.lang.Keyword
                      clojure.lang.PersistentArrayMap
                      clojure.lang.PersistentHashSet
                      clojure.lang.PersistentList
                      clojure.lang.PersistentQueue
                      clojure.lang.PersistentStructMap
                      clojure.lang.PersistentTreeMap
                      clojure.lang.PersistentTreeSet
                      clojure.lang.PersistentVector
                      clojure.lang.Ratio
                      clojure.lang.ReaderConditional
                      clojure.lang.Repeat
                      clojure.lang.Reversible
                      clojure.lang.Sorted
                      clojure.lang.Symbol
                      clojure.lang.Sequential
                      clojure.lang.Seqable
                      clojure.lang.Volatile
                      ;; the only way to check if something is a channel is to
                      ;; call instance? on this...
                      clojure.core.async.impl.channels.ManyToManyChannel
                      java.lang.AbstractMethodError
                      java.lang.ExceptionInInitializerError
                      java.lang.LinkageError
                      java.lang.ThreadDeath
                      java.lang.VirtualMachineError
                      java.sql.Timestamp
                      java.util.concurrent.TimeoutException
                      java.util.Collection
                      java.util.Map$Entry
                      ~@(when features/xml? ['clojure.data.xml.node.Element])]
    :custom ~custom-map})

(defmacro gen-class-map []
  (let [classes (concat (:all classes)
                        (keys (:custom classes))
                        (:constructors classes)
                        (:methods classes)
                        (:fields classes)
                        (:instance-checks classes))
        m (apply hash-map
                 (for [c classes
                       c [(list 'quote c) c]]
                   c))
        m (assoc m :public-class
                 (fn [v]
                   ;; NOTE: a series of instance check, so far, is still cheaper
                   ;; than piggybacking on defmulti or defprotocol
                   (cond (instance? java.lang.Process v)
                         java.lang.Process
                         (instance? java.lang.ProcessHandle v)
                         java.lang.ProcessHandle
                         (instance? java.lang.ProcessHandle$Info v)
                         java.lang.ProcessHandle$Info
                         ;; added for calling .put on .environment from ProcessBuilder
                         (instance? java.util.Map v)
                         java.util.Map
                         ;; added for issue #239 regarding clj-http-lite
                         ;; can potentially be removed due to fix for #1061
                         (instance? java.io.ByteArrayOutputStream v)
                         java.io.ByteArrayOutputStream
                         (instance? java.security.MessageDigest v)
                         java.security.MessageDigest
                         ;; streams
                         (instance? java.io.InputStream v)
                         java.io.InputStream
                         (instance? java.io.OutputStream v)
                         java.io.OutputStream
                         ;; java nio
                         (instance? java.nio.file.Path v)
                         java.nio.file.Path
                         (instance? java.nio.file.FileSystem v)
                         java.nio.file.FileSystem
                         (instance? java.nio.file.PathMatcher v)
                         java.nio.file.PathMatcher
                         (instance? java.util.stream.BaseStream v)
                         java.util.stream.BaseStream
                         (instance? java.nio.ByteBuffer v)
                         java.nio.ByteBuffer
                         (instance? java.nio.charset.Charset v)
                         java.nio.charset.Charset
                         (instance? java.nio.charset.CharsetEncoder v)
                         java.nio.charset.CharsetEncoder
                         (instance? java.nio.CharBuffer v)
                         java.nio.CharBuffer
                         (instance? java.nio.channels.FileChannel v)
                         java.nio.channels.FileChannel
                         (instance? java.net.CookieStore v)
                         java.net.CookieStore
                         ;; this makes interop on reified classes work
                         ;; see java_net_http_test/interop-test
                         (instance? sci.impl.types.IReified v)
                         (first (t/getInterfaces v))
                         ;; fix for #1061
                         (instance? java.io.Closeable v)
                         java.io.Closeable
                         (instance? java.nio.file.attribute.BasicFileAttributes v)
                         java.nio.file.attribute.BasicFileAttributes
                         (instance? java.util.concurrent.Future v)
                         java.util.concurrent.Future
                         (instance? java.util.concurrent.ScheduledExecutorService v)
                         java.util.concurrent.ScheduledExecutorService
                         (instance? java.util.Iterator v)
                         java.util.Iterator
                         ;; keep commas for merge friendliness
                         ,,,)))
        m (assoc m (list 'quote 'clojure.lang.Var) 'sci.lang.Var)]
    m))


(def class-map*
  "This contains mapping of symbol to class of all classes that are
  allowed to be initialized at build time."
  (gen-class-map))

(def class-map
  "A delay to delay initialization of java-net-http classes to run time, since GraalVM 22.1"
  (delay (persistent! (reduce (fn [acc c]
                                (assoc! acc c (Class/forName (str c))))
                              (transient class-map*) (when features/java-net-http?
                                                       java-net-http-classes)))))

(def imports
  '{AbstractMethodError java.lang.AbstractMethodError
    Appendable java.lang.Appendable
    ArithmeticException java.lang.ArithmeticException
    AssertionError java.lang.AssertionError
    BigDecimal java.math.BigDecimal
    BigInteger java.math.BigInteger
    Boolean java.lang.Boolean
    Byte java.lang.Byte
    Character java.lang.Character
    CharSequence java.lang.CharSequence
    Class java.lang.Class
    ClassCastException java.lang.ClassCastException
    ClassNotFoundException java.lang.ClassNotFoundException
    Comparable java.lang.Comparable
    Double java.lang.Double
    Exception java.lang.Exception
    ExceptionInInitializerError java.lang.ExceptionInInitializerError
    IndexOutOfBoundsException java.lang.IndexOutOfBoundsException
    IllegalArgumentException java.lang.IllegalArgumentException
    IllegalStateException java.lang.IllegalStateException
    Integer java.lang.Integer
    InterruptedException java.lang.InterruptedException
    Iterable java.lang.Iterable
    File java.io.File
    Float java.lang.Float
    Long java.lang.Long
    LinkageError java.lang.LinkageError
    Math java.lang.Math
    NullPointerException java.lang.NullPointerException
    Number java.lang.Number
    NumberFormatException java.lang.NumberFormatException
    Object java.lang.Object
    Runtime java.lang.Runtime
    RuntimeException java.lang.RuntimeException
    Process        java.lang.Process
    ProcessBuilder java.lang.ProcessBuilder
    Short java.lang.Short
    StackTraceElement java.lang.StackTraceElement
    String java.lang.String
    StringBuilder java.lang.StringBuilder
    System java.lang.System
    Thread java.lang.Thread
    Throwable java.lang.Throwable
    VirtualMachineError java.lang.VirtualMachineError
    ThreadDeath java.lang.ThreadDeath
    ;; UnsupportedOperationException java.lang.UnsupportedOperationException
    })

(defn reflection-file-entries []
  (let [entries (vec (for [c (sort (concat (:all classes)
                                           (when features/java-net-http?
                                             java-net-http-classes)))
                           :let [class-name (str c)]]
                       {:name class-name
                        :allPublicMethods true
                        :allPublicFields true
                        :allPublicConstructors true}))
        constructors (vec (for [c (sort (:constructors classes))
                                :let [class-name (str c)]]
                            {:name class-name
                             :allPublicConstructors true}))
        methods (vec (for [c (sort (:methods classes))
                           :let [class-name (str c)]]
                       {:name class-name
                        :allPublicMethods true}))
        fields (vec (for [c (sort (:fields classes))
                          :let [class-name (str c)]]
                      {:name class-name
                       :allPublicFields true}))
        instance-checks (vec (for [c (sort (:instance-checks classes))
                                   :let [class-name (str c)]]
                               ;; don't include any methods
                               {:name class-name}))
        custom-entries (for [[c v] (:custom classes)
                             :let [class-name (str c)]]
                         (assoc v :name class-name))
        all-entries (concat entries constructors methods fields instance-checks custom-entries)]
    all-entries))

(defn generate-reflection-file
  "Generate reflect-config.json file"
  [& args]
  (let [all-entries (reflection-file-entries)]
    (spit (or
           (first args)
           "resources/META-INF/native-image/babashka/babashka/reflect-config.json")
          (json/generate-string all-entries {:pretty true}))))

(defn public-declared-method? [c m]
  (and (= c (.getDeclaringClass m))
       (not (.getAnnotation m Deprecated))))

(defn public-declared-method-names [c]
  (->> (.getMethods c)
       (keep (fn [m]
               (when (public-declared-method? c m)
                 {:class c
                  :name (.getName m)})))
       (distinct)
       (sort-by :name)
       (vec)))

(defn all-classes []
  "Returns every java.lang.Class instance Babashka supports."
  (->> (reflection-file-entries)
       (map :name)
       (map #(Class/forName %))))

(defn all-methods []
  (mapcat public-declared-method-names (all-classes)))

(def cns (sci/create-ns 'babashka.classes nil))

(def classes-namespace
  {:obj cns
   'all-classes (sci/copy-var all-classes cns)})

(comment
  (public-declared-method-names java.net.URL)
  (public-declared-method-names java.util.Properties)

  (all-classes)

  )
