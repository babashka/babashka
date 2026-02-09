(ns babashka.impl.classes
  {:no-doc true}
  (:require
   [babashka.impl.common :refer [ctx]]
   [babashka.impl.features :as features]
   [babashka.impl.proxy :as proxy]
   [cheshire.core :as json]
   [clojure.core.async]
   [sci.core :as sci]
   [sci.impl.load]
   [sci.impl.types :as t]
   [sci.impl.vars :as vars]))

(set! *warn-on-reflection* true)

(def has-of-virtual?
  (some #(= "ofVirtual" (.getName ^java.lang.reflect.Method %))
        (.getMethods Thread)))

(def has-domain-sockets?
  (resolve 'java.net.UnixDomainSocketAddress))

(def has-graal-process-properties?
  (resolve 'org.graalvm.nativeimage.ProcessProperties))

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
               {:name "yield"}
               ~@(when has-of-virtual? [{:name "ofVirtual"}
                                        {:name "startVirtualThread"}
                                        {:name "isVirtual"}])]}
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
               {:name "equals"}
               {:name "fill"}]}
    ;; this fixes clojure.lang.Reflector for Java 11
    java.lang.reflect.AccessibleObject
    {:methods [{:name "canAccess"}]}
    java.lang.Package
    {:methods [{:name "getName"}]}
    java.lang.reflect.Member
    {:methods [{:name "getModifiers"}]}
    java.lang.reflect.Method
    {:methods [{:name "invoke"}
               {:name "getName"}
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
     :methods [{:name "getMethod"}
               {:name "addMethod"}]}
    clojure.lang.RT
    {:methods [{:name "aget"}
               {:name "aset"}
               {:name "aclone"}
               {:name "iter"}
               ;; we expose this via the Compiler/LOADER dynamic var
               {:name "baseLoader"}]}
    clojure.lang.Compiler
    {:fields [{:name "specials"}
              {:name "CHAR_MAP"}]
     :methods [{:name "demunge"}]}
    clojure.lang.PersistentHashMap
    {:fields [{:name "EMPTY"}]}
    clojure.lang.APersistentVector
    {:methods [{:name "indexOf"}
               {:name "contains"}]}
    clojure.lang.LazySeq
    {:allPublicConstructors true,
     :methods [{:name "indexOf"}
               {:name "contains"}]}
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
    {:methods [{:name "getTimeZone"}
               {:name "setDefault"}]}
    java.net.URLClassLoader
    {:methods [{:name "close"}
               {:name "findResource"}
               {:name "findResources"}
               {:name "getResourceAsStream"}
               {:name "getURLs"}]}
    java.lang.ClassLoader
    {:methods [{:name "getResource"}
               {:name "getResources"}
               {:name "getResourceAsStream"}
               {:name "getParent"}]}
    clojure.lang.ARef
    {:methods [{:name "getWatches"}]}
    clojure.lang.MapEntry
    {:allPublicConstructors true
     :methods [{:name "create"}]}
    clojure.lang.TaggedLiteral
    {:methods [{:name "create"}]}})

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
                                                         "java.lang.ClassLoader"]}]})

    has-graal-process-properties?
    (assoc `org.graalvm.nativeimage.ProcessProperties
           {:methods [{:name "exec"}
                      {:name "getExecutableName"}]})))

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
    java.security.cert.CertificateFactory
    java.security.Signature
    javax.crypto.Cipher
    javax.crypto.KeyAgreement
    javax.crypto.Mac
    javax.crypto.SecretKey
    javax.crypto.SecretKeyFactory
    javax.crypto.spec.GCMParameterSpec
    javax.crypto.spec.IvParameterSpec
    javax.crypto.spec.PBEKeySpec
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
    javax.net.ssl.X509ExtendedTrustManager
    javax.net.ssl.SSLSocket
    javax.net.ssl.SSLSocketFactory
    jdk.internal.net.http.HttpClientBuilderImpl
    jdk.internal.net.http.HttpClientFacade
    jdk.internal.net.http.HttpRequestBuilderImpl
    jdk.internal.net.http.HttpResponseImpl
    jdk.internal.net.http.common.MinimalFuture
    jdk.internal.net.http.websocket.BuilderImpl
    jdk.internal.net.http.websocket.WebSocketImpl])

(def thread-builder
  (try (Class/forName "java.lang.Thread$Builder")
       (catch Exception _ nil)))

(def thread-builder-of-platform
  (try (Class/forName "java.lang.Thread$Builder$OfPlatform")
       (catch Exception _ nil)))

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
          java.io.Flushable
          java.io.LineNumberReader
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
          java.lang.AutoCloseable
          java.lang.Boolean
          java.lang.Byte
          java.lang.Character
          java.lang.Character$UnicodeBlock
          java.lang.CharSequence
          java.lang.Class
          java.lang.ClassCastException
          java.lang.ClassNotFoundException
          java.lang.Comparable
          java.lang.Double
          java.lang.Error
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
          java.lang.SecurityException
          java.lang.Short
          java.lang.StackTraceElement
          java.lang.String
          java.lang.StringBuilder
          java.lang.System
          java.lang.Throwable
          java.lang.ThreadLocal
          java.lang.Thread$UncaughtExceptionHandler
          ~@(when thread-builder
              '[java.lang.Thread$Builder
                java.lang.Thread$Builder$OfPlatform])
          java.lang.UnsupportedOperationException
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
          java.net.JarURLConnection
          java.net.StandardProtocolFamily
          java.net.ServerSocket
          java.net.Socket
          java.net.SocketException
          ~@(when has-domain-sockets?
              '[java.net.UnixDomainSocketAddress])
          java.net.UnknownHostException
          java.net.URI
          java.net.URISyntaxException
          ;; java.net.URL, see custom map
          java.net.URLConnection
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
                java.nio.channels.ByteChannel
                java.nio.channels.Channels
                java.nio.channels.FileChannel
                java.nio.channels.FileChannel$MapMode
                java.nio.channels.ReadableByteChannel
                java.nio.channels.WritableByteChannel
                java.nio.channels.ServerSocketChannel
                java.nio.channels.SocketChannel
                java.nio.charset.Charset
                java.nio.charset.CoderResult
                java.nio.charset.CodingErrorAction
                java.nio.charset.CharacterCodingException
                java.nio.charset.CharsetEncoder
                java.nio.charset.CharsetDecoder
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
                java.nio.file.DirectoryStream$Filter
                java.nio.file.LinkOption
                java.nio.file.NoSuchFileException
                java.nio.file.Path
                java.nio.file.PathMatcher
                java.nio.file.Paths
                java.nio.file.StandardCopyOption
                java.nio.file.attribute.BasicFileAttributes
                java.nio.file.attribute.FileAttribute
                java.nio.file.attribute.FileTime
                java.nio.file.attribute.GroupPrincipal
                java.nio.file.attribute.PosixFileAttributes
                java.nio.file.attribute.PosixFilePermission
                java.nio.file.attribute.PosixFilePermissions
                java.nio.file.attribute.UserDefinedFileAttributeView
                java.nio.file.attribute.UserPrincipal])
          java.security.DigestInputStream
          java.security.DigestOutputStream
          java.security.KeyFactory
          java.security.KeyPairGenerator
          java.security.KeyPair
          java.security.KeyStore
          java.security.MessageDigest
          java.security.Provider
          java.security.SecureRandom
          java.security.Security
          java.security.spec.ECGenParameterSpec
          java.security.spec.PKCS8EncodedKeySpec
          java.security.spec.X509EncodedKeySpec
          java.sql.Date
          java.text.ParseException
          java.text.ParsePosition
          java.text.Normalizer
          java.text.Normalizer$Form
          ;; adds about 200kb, same functionality provided by java.time:
          java.text.SimpleDateFormat
          java.text.BreakIterator
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
                java.time.zone.ZoneRules
                java.time.ZonedDateTime
                java.time.ZoneId
                java.time.ZoneOffset
                java.time.format.DateTimeFormatterBuilder
                java.time.format.DateTimeParseException
                java.time.format.DecimalStyle
                java.time.format.FormatStyle
                java.time.format.ResolverStyle
                java.time.format.SignStyle
                java.time.temporal.ChronoField
                java.time.temporal.ChronoUnit
                java.time.temporal.IsoFields
                java.time.temporal.TemporalAdjusters
                java.time.temporal.TemporalAmount
                java.time.temporal.TemporalField
                java.time.temporal.ValueRange
                java.time.temporal.WeekFields
                ~(symbol "[Ljava.time.temporal.TemporalField;")
                java.time.format.TextStyle
                java.time.temporal.Temporal
                java.time.temporal.TemporalAccessor
                java.time.temporal.TemporalAdjuster
                java.time.temporal.TemporalQuery
                ~(symbol "[Ljava.time.temporal.TemporalQuery;")
                java.time.chrono.ChronoLocalDate
                java.time.temporal.TemporalUnit
                java.time.chrono.ChronoLocalDateTime
                java.time.chrono.ChronoZonedDateTime
                java.time.chrono.Chronology])
          java.util.concurrent.atomic.AtomicInteger
          java.util.concurrent.atomic.AtomicLong
          java.util.concurrent.atomic.AtomicReference
          java.util.concurrent.Callable
          java.util.concurrent.CancellationException
          java.util.concurrent.CompletionException
          java.util.concurrent.CountDownLatch
          java.util.concurrent.ExecutionException
          java.util.concurrent.Executor
          java.util.concurrent.ExecutorService
          java.util.concurrent.BlockingQueue
          java.util.concurrent.ArrayBlockingQueue
          java.util.concurrent.LinkedBlockingQueue
          java.util.concurrent.ScheduledFuture
          java.util.concurrent.ScheduledThreadPoolExecutor
          java.util.concurrent.Semaphore
          java.util.concurrent.ThreadFactory
          java.util.concurrent.ThreadPoolExecutor
          java.util.concurrent.ThreadPoolExecutor$AbortPolicy
          java.util.concurrent.ThreadPoolExecutor$CallerRunsPolicy
          java.util.concurrent.ThreadPoolExecutor$DiscardOldestPolicy
          java.util.concurrent.ThreadPoolExecutor$DiscardPolicy
          java.util.concurrent.TimeoutException
          java.util.concurrent.ExecutorService
          java.util.concurrent.ScheduledExecutorService
          java.util.concurrent.ForkJoinPool
          java.util.concurrent.ForkJoinPool$ForkJoinWorkerThreadFactory
          java.util.concurrent.ForkJoinWorkerThread
          java.util.concurrent.Future
          java.util.concurrent.FutureTask
          java.util.concurrent.CompletableFuture
          java.util.concurrent.Executors
          java.util.concurrent.TimeUnit
          java.util.concurrent.CompletionStage
          java.util.concurrent.locks.ReentrantLock
          java.util.concurrent.ThreadLocalRandom
          java.util.concurrent.ConcurrentHashMap
          java.util.concurrent.SynchronousQueue
          java.util.jar.Attributes
          java.util.jar.Attributes$Name
          java.util.jar.JarFile
          java.util.jar.JarEntry
          java.util.jar.JarFile$JarFileEntry
          java.util.jar.JarInputStream
          java.util.jar.JarOutputStream
          java.util.jar.Manifest
          java.util.stream.BaseStream
          java.util.stream.Stream
          java.util.stream.IntStream
          java.util.Random
          java.util.regex.Matcher
          java.util.regex.Pattern
          java.util.regex.PatternSyntaxException
          java.util.ArrayDeque
          java.util.ArrayList
          java.util.Collections
          java.util.Comparator
          java.util.Base64
          java.util.Base64$Decoder
          java.util.Base64$Encoder
          java.util.Date
          java.util.HashMap
          java.util.HashSet
          java.util.IdentityHashMap
          java.util.InputMismatchException
          java.util.LinkedList
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
          java.util.zip.CheckedInputStream
          java.util.zip.CRC32
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
          ~@(when features/hsqldb? '[org.hsqldb.jdbcDriver])
          org.jsoup.Jsoup
          org.jsoup.nodes.Attribute
          org.jsoup.nodes.Attributes
          org.jsoup.nodes.Comment
          org.jsoup.nodes.DataNode
          org.jsoup.nodes.Document
          org.jsoup.nodes.DocumentType
          org.jsoup.nodes.Element
          org.jsoup.nodes.Node
          org.jsoup.nodes.TextNode
          org.jsoup.nodes.XmlDeclaration
          org.jsoup.parser.Tag
          org.jsoup.parser.Parser
          ;; jline
          org.jline.terminal.Terminal
          org.jline.terminal.TerminalBuilder
          org.jline.terminal.Attributes
          org.jline.utils.AttributedString
          org.jline.utils.AttributedStringBuilder
          org.jline.utils.AttributedStyle
          org.jline.utils.InfoCmp$Capability
          org.jline.utils.NonBlockingReader
          org.jline.utils.Display
          org.jline.utils.Signals
          org.jline.terminal.Size
          org.jline.reader.LineReader
          org.jline.reader.LineReaderBuilder
          org.jline.reader.EndOfFileException
          org.jline.reader.UserInterruptException
          org.jline.keymap.KeyMap
          org.jline.terminal.Terminal$SignalHandler
          org.jline.terminal.spi.TerminalProvider
          org.jline.terminal.spi.TerminalExt ;; cast Terminal to this and then .getProvider
          org.jline.terminal.spi.SystemStream
          ;; end jline
          ]
    :constructors [clojure.lang.Delay
                   clojure.lang.DynamicClassLoader
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
                      clojure.lang.ITransientCollection
                      clojure.lang.ITransientSet
                      clojure.lang.ITransientVector
                      clojure.lang.Iterate
                      clojure.lang.LispReader$Resolver
                      clojure.lang.LongRange
                      clojure.lang.Named
                      clojure.lang.Keyword
                      clojure.lang.PersistentArrayMap
                      clojure.lang.PersistentArrayMap$TransientArrayMap
                      clojure.lang.PersistentHashMap$TransientHashMap
                      clojure.lang.PersistentHashSet
                      clojure.lang.PersistentHashSet$TransientHashSet
                      clojure.lang.PersistentList
                      clojure.lang.PersistentList$EmptyList
                      clojure.lang.PersistentQueue
                      clojure.lang.PersistentStructMap
                      clojure.lang.PersistentTreeMap
                      clojure.lang.PersistentTreeSet
                      clojure.lang.PersistentVector
                      clojure.lang.PersistentVector$TransientVector
                      clojure.lang.Range
                      clojure.lang.Ratio
                      clojure.lang.ReaderConditional
                      clojure.lang.Ref
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
                      java.lang.NoSuchFieldException
                      java.sql.Timestamp
                      java.util.Collection
                      java.util.Map$Entry
                      java.util.AbstractMap
                      java.util.AbstractSet
                      java.util.AbstractList
                      ~@(when features/xml? ['clojure.data.xml.node.Element])]
    :custom ~custom-map})

(defn compiler-load
  ([this ^java.io.Reader rdr]
   (compiler-load this rdr "NO_SOURCE_PATH" "NO_SOURCE_FILE"))
  ([_ ^java.io.Reader rdr ^String source-path ^String _source-name]
   (sci/binding [sci/file source-path]
     (sci.impl.load/load-reader rdr))))

(defmacro gen-class-map []
  (let [classes (concat (:all classes)
                        (keys (:custom classes))
                        (:constructors classes)
                        (:methods classes)
                        (:fields classes)
                        (:instance-checks classes))
        m (apply hash-map
                 (for [c classes
                       c [(list 'quote c)
                          (cond-> `{:class ~c}
                            (= 'java.lang.Class c)
                            (assoc :static-methods
                                   {(list 'quote 'forName)
                                    `(fn
                                       ([_# ^String class-name#]
                                        (Class/forName class-name#))
                                       ([_# ^String class-name# initialize# ^java.lang.ClassLoader clazz-loader#]
                                        (Class/forName class-name#)))})
                            (= 'clojure.lang.Compiler c)
                            (assoc :static-methods
                                   {(list 'quote 'load)
                                    `compiler-load}))]]
                   c))
        m (assoc m :public-class
                 (fn [v]
                   ;; (prn :v v)
                   ;; NOTE: a series of instance check, so far, is still cheaper
                   ;; than piggybacking on defmulti or defprotocol
                   (let [res (cond (instance? java.lang.Process v)
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
                                   (instance? java.util.stream.Stream v)
                                   java.util.stream.Stream
                                   (instance? java.util.stream.IntStream v)
                                   java.util.stream.IntStream
                                   (instance? java.util.stream.BaseStream v)
                                   java.util.stream.BaseStream
                                   (instance? java.nio.ByteBuffer v)
                                   java.nio.ByteBuffer
                                   (instance? java.nio.charset.Charset v)
                                   java.nio.charset.Charset
                                   (instance? java.nio.charset.CharsetEncoder v)
                                   java.nio.charset.CharsetEncoder
                                   (instance? java.nio.charset.CharsetDecoder v)
                                   java.nio.charset.CharsetDecoder
                                   (instance? java.nio.CharBuffer v)
                                   java.nio.CharBuffer
                                   (instance? java.nio.channels.FileChannel v)
                                   java.nio.channels.FileChannel
                                   (instance? java.nio.channels.ServerSocketChannel v)
                                   java.nio.channels.ServerSocketChannel
                                   (instance? java.nio.channels.SocketChannel v)
                                   java.nio.channels.SocketChannel
                                   (instance? java.net.CookieStore v)
                                   java.net.CookieStore
                                   ;; this makes interop on reified classes work
                                   ;; see java_net_http_test/interop-test
                                   (instance? sci.impl.types.IReified v)
                                   (first (t/getInterfaces v))
                                   ;; fix for #1061
                                   (instance? java.net.URLClassLoader v)
                                   java.net.URLClassLoader
                                   (instance? java.lang.ClassLoader v)
                                   java.lang.ClassLoader
                                   (instance? java.nio.file.attribute.PosixFileAttributes v)
                                   java.nio.file.attribute.PosixFileAttributes
                                   (instance? java.nio.file.attribute.BasicFileAttributes v)
                                   java.nio.file.attribute.BasicFileAttributes
                                   (instance? java.nio.file.attribute.GroupPrincipal v)
                                   java.nio.file.attribute.GroupPrincipal
                                   (instance? java.nio.file.attribute.UserDefinedFileAttributeView v)
                                   java.nio.file.attribute.UserDefinedFileAttributeView
                                   (instance? java.nio.file.attribute.UserPrincipal v)
                                   java.nio.file.attribute.UserPrincipal
                                   (instance? java.util.concurrent.Future v)
                                   java.util.concurrent.Future
                                   (instance? java.util.concurrent.ScheduledExecutorService v)
                                   java.util.concurrent.ScheduledExecutorService
                                   (instance? java.util.concurrent.ExecutorService v)
                                   java.util.concurrent.ExecutorService
                                   (instance? java.util.Iterator v)
                                   java.util.Iterator
                                   (instance? javax.crypto.SecretKey v)
                                   javax.crypto.SecretKey
                                   (instance? javax.net.ssl.SSLSocketFactory v)
                                   javax.net.ssl.SSLSocketFactory
                                   (instance? javax.net.ssl.SSLSocket v)
                                   javax.net.ssl.SSLSocket
                                   (instance? java.lang.Thread v)
                                   java.lang.Thread
                                   (instance? java.util.concurrent.ThreadFactory v)
                                   java.util.concurrent.ThreadFactory
                                   (instance? java.security.cert.X509Certificate v)
                                   java.security.cert.X509Certificate
                                   (instance? java.io.Console v)
                                   java.io.Console
                                   (instance? java.security.KeyPairGenerator v)
                                   java.security.KeyPairGenerator
                                   (instance? java.security.Signature v)
                                   java.security.Signature
                                   (instance? java.security.Key v)
                                   java.security.Key
                                   (instance? java.util.Set v)
                                   java.util.Set
                                   (instance? org.jline.reader.LineReader v)
                                   org.jline.reader.LineReader
                                   ;; jline: check before Closeable since Terminal extends Closeable
                                   (instance? org.jline.terminal.Terminal v)
                                   org.jline.terminal.Terminal
                                   ;; jline: check before Closeable since NonBlockingReader extends Closeable
                                   (instance? org.jline.utils.NonBlockingReader v)
                                   org.jline.utils.NonBlockingReader
                                   (instance? org.jline.terminal.spi.TerminalProvider v)
                                   org.jline.terminal.spi.TerminalProvider
                                   (instance? java.io.Closeable v)
                                   java.io.Closeable
                                   (instance? java.util.Collection v)
                                   java.util.Collection
                                   (instance? java.lang.Throwable v)
                                   java.lang.Throwable
                                   (instance? org.jsoup.nodes.Element v)
                                   org.jsoup.nodes.Element
                                   (and thread-builder-of-platform
                                        (instance? thread-builder-of-platform v))
                                   thread-builder-of-platform
                                   (and thread-builder
                                        (instance? thread-builder v))
                                   thread-builder
                                   (instance? java.text.BreakIterator v)
                                   java.text.BreakIterator
                                   ;; keep commas for merge friendliness
                                   ,)]
                     ;; (prn :res res)
                     res)))
        m (assoc m (list 'quote 'clojure.lang.Var)
                 {:class 'sci.lang.Var
                  :static-methods {(list 'quote 'cloneThreadBindingFrame) `(fn [_#]
                                                                             (vars/clone-thread-binding-frame))
                                   (list 'quote 'resetThreadBindingFrame) `(fn [_# frame#]
                                                                             (vars/reset-thread-binding-frame frame#))
                                   (list 'quote 'getThreadBindingFrame) `(fn [_#]
                                                                           (vars/get-thread-binding-frame))
                                   (list 'quote 'intern) `(fn [_# & args#]
                                                            (apply sci/intern (ctx) args#))}})
        m (assoc m (list 'quote 'clojure.lang.Namespace) 'sci.lang.Namespace)]
    m))


(def class-map*
  "This contains mapping of symbol to class of all classes that are
  allowed to be initialized at build time."
  (gen-class-map))

;; (prn :class-map* class-map*)

#_(let [class-name (str c)]
    (cond-> (Class/forName class-name)
      (= "java.lang.Class" class-name)
      (->> (hash-map :static-methods {'forName (fn [class-name]
                                                 (prn :class-for)
                                                 (Class/forName class-name))}
                     :class))))

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
    Callable java.util.concurrent.Callable
    Character java.lang.Character
    CharSequence java.lang.CharSequence
    Class java.lang.Class
    ClassCastException java.lang.ClassCastException
    ClassNotFoundException java.lang.ClassNotFoundException
    Comparable java.lang.Comparable
    Compiler clojure.lang.Compiler
    Double java.lang.Double
    Error java.lang.Error
    Exception java.lang.Exception
    ExceptionInInitializerError java.lang.ExceptionInInitializerError
    IndexOutOfBoundsException java.lang.IndexOutOfBoundsException
    IllegalArgumentException java.lang.IllegalArgumentException
    IllegalStateException java.lang.IllegalStateException
    Integer java.lang.Integer
    InterruptedException java.lang.InterruptedException
    Iterable java.lang.Iterable
    ;; NOTE: in hindsight File never belonged to the default imports of Clojure,
    ;; but it's been here to long to remove probably
    File java.io.File
    Float java.lang.Float
    Long java.lang.Long
    LinkageError java.lang.LinkageError
    Math java.lang.Math
    NullPointerException java.lang.NullPointerException
    Number java.lang.Number
    NumberFormatException java.lang.NumberFormatException
    Object java.lang.Object
    Runnable java.lang.Runnable
    Runtime java.lang.Runtime
    RuntimeException java.lang.RuntimeException
    Process java.lang.Process
    ProcessBuilder java.lang.ProcessBuilder
    SecurityException java.lang.SecurityException
    Short java.lang.Short
    StackTraceElement java.lang.StackTraceElement
    String java.lang.String
    StringBuilder java.lang.StringBuilder
    System java.lang.System
    Thread java.lang.Thread
    ThreadLocal java.lang.ThreadLocal
    Thread$UncaughtExceptionHandler java.lang.Thread$UncaughtExceptionHandler
    Throwable java.lang.Throwable
    VirtualMachineError java.lang.VirtualMachineError
    ThreadDeath java.lang.ThreadDeath
    UnsupportedOperationException java.lang.UnsupportedOperationException})

;; (eval (vec (keys imports)))

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

(defn public-declared-method? [^Class c ^java.lang.reflect.Method m]
  (and (= c (.getDeclaringClass m))
       (not (.getAnnotation m Deprecated))))

(defn public-declared-method-names [^Class c]
  (->> (.getMethods c)
       (keep (fn [^java.lang.reflect.Method m]
               (when (public-declared-method? c m)
                 {:class c
                  :name (.getName m)})))
       (distinct)
       (sort-by :name)
       (vec)))

(defn all-classes
  "Returns every java.lang.Class instance Babashka supports."
  []
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

  (all-classes))
