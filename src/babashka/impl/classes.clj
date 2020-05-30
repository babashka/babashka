(ns babashka.impl.classes
  {:no-doc true}
  (:require
   [babashka.impl.features :as features]
   [cheshire.core :as json]))

(def custom-map
  (cond->
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
        {:methods [{:name "copyOf"}]}}
    features/hsqldb? (assoc `org.hsqldb.dbinfo.DatabaseInformationFull
                            {:methods [{:name "<init>"
                                        :parameterTypes ["org.hsqldb.Database"]}]}
                            `java.util.ResourceBundle
                            {:methods [{:name "getBundle"
                                        :parameterTypes ["java.lang.String","java.util.Locale","java.lang.ClassLoader"]}]})))

(def classes
  `{:all [clojure.lang.ExceptionInfo
          java.io.BufferedReader
          java.io.BufferedWriter
          java.io.ByteArrayInputStream
          java.io.ByteArrayOutputStream
          java.io.File
          java.io.InputStream
          java.io.IOException
          java.io.OutputStream
          java.io.FileReader
          java.io.InputStreamReader
          java.io.PushbackInputStream
          java.io.Reader
          java.io.SequenceInputStream
          java.io.StringReader
          java.io.StringWriter
          java.io.Writer
          java.lang.ArithmeticException
          java.lang.AssertionError
          java.lang.Boolean
          java.lang.Byte
          java.lang.Character
          java.lang.Class
          java.lang.ClassNotFoundException
          java.lang.Comparable
          java.lang.Double
          java.lang.Exception
          java.lang.Float
          java.lang.Integer
          java.lang.Long
          java.lang.Number
          java.lang.NumberFormatException
          java.lang.Math
          java.lang.Object
          java.lang.Process
          java.lang.ProcessBuilder
          java.lang.ProcessBuilder$Redirect
          java.lang.Runtime
          java.lang.RuntimeException
          java.lang.String
          java.lang.StringBuilder
          java.lang.System
          java.lang.Throwable
          java.math.BigDecimal
          java.math.BigInteger
          java.net.DatagramSocket
          java.net.DatagramPacket
          java.net.HttpURLConnection
          java.net.InetAddress
          java.net.ServerSocket
          java.net.Socket
          java.net.UnknownHostException
          java.net.URI
          ;; java.net.URL, see below
          java.net.URLEncoder
          java.net.URLDecoder
          ~@(when features/java-nio?
              '[java.nio.file.OpenOption
                java.nio.file.CopyOption
                java.nio.file.FileAlreadyExistsException
                java.nio.file.FileSystem
                java.nio.file.FileSystems
                java.nio.file.Files
                java.nio.file.LinkOption
                java.nio.file.NoSuchFileException
                java.nio.file.Path
                java.nio.file.PathMatcher
                java.nio.file.Paths
                java.nio.file.StandardCopyOption
                java.nio.file.attribute.FileAttribute
                java.nio.file.attribute.FileTime
                java.nio.file.attribute.PosixFilePermission
                java.nio.file.attribute.PosixFilePermissions])
          java.security.MessageDigest
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
                java.time.format.DecimalStyle
                java.time.format.ResolverStyle
                java.time.format.SignStyle
                java.time.temporal.ChronoField
                java.time.temporal.ChronoUnit
                java.time.temporal.IsoFields
                java.time.temporal.TemporalField
                ~(symbol "[Ljava.time.temporal.TemporalField;")
                java.time.format.TextStyle
                java.time.temporal.Temporal
                java.time.temporal.TemporalAccessor
                java.time.temporal.TemporalAdjuster])
          java.util.concurrent.LinkedBlockingQueue
          java.util.jar.JarFile
          java.util.jar.JarEntry
          java.util.jar.JarFile$JarFileEntry
          java.util.regex.Pattern
          java.util.Base64
          java.util.Base64$Decoder
          java.util.Base64$Encoder
          java.util.Date
          java.util.MissingResourceException
          java.util.Properties
          java.util.Set
          java.util.UUID
          java.util.concurrent.TimeUnit
          java.util.zip.InflaterInputStream
          java.util.zip.DeflaterInputStream
          java.util.zip.GZIPInputStream
          java.util.zip.GZIPOutputStream
          ~(symbol "[B")
          ~@(when features/yaml? '[org.yaml.snakeyaml.error.YAMLException])
          ~@(when features/hsqldb? '[org.hsqldb.jdbcDriver])]
    :constructors [clojure.lang.Delay
                   clojure.lang.MapEntry
                   clojure.lang.LineNumberingPushbackReader
                   java.io.EOFException
                   java.io.PrintWriter
                   java.io.PushbackReader]
    :methods [borkdude.graal.LockFix ;; support for locking
              ]
    :fields [clojure.lang.PersistentQueue]
    :instance-checks [clojure.lang.IObj
                      clojure.lang.IEditableCollection]
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
                   c))]
    (assoc m :public-class
           (fn [v]
             (cond (instance? java.nio.file.Path v)
                   java.nio.file.Path
                   (instance? java.lang.Process v)
                   java.lang.Process
                   ;; added for issue #239 regarding clj-http-lite
                   (instance? java.io.ByteArrayOutputStream v)
                   java.io.ByteArrayOutputStream
                   (instance? java.security.MessageDigest v)
                   java.security.MessageDigest
                   (instance? java.io.InputStream v)
                   java.io.InputStream
                   (instance? java.io.OutputStream v)
                   java.io.OutputStream
                   (instance? java.nio.file.FileSystem v)
                   java.nio.file.FileSystem
                   (instance? java.nio.file.PathMatcher v)
                   java.nio.file.PathMatcher)))))

(def class-map (gen-class-map))

(defn generate-reflection-file
  "Generate reflection.json file"
  [& args]
  (let [entries (vec (for [c (sort (:all classes))
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
        custom-entries (for [[c v] (:custom classes)
                             :let [class-name (str c)]]
                         (assoc v :name class-name))
        all-entries (concat entries constructors methods fields custom-entries)]
    (spit (or
           (first args)
           "reflection.json") (json/generate-string all-entries {:pretty true}))))

(comment

  (defn public-declared-method? [c m]
    (and (= c (.getDeclaringClass m))
         (not (.getAnnotation m Deprecated))))

  (defn public-declared-method-names [c]
    (->> (.getMethods c)
         (keep (fn [m]
                 (when (public-declared-method? c m)
                   {:name (.getName m)})) )
         (distinct)
         (sort-by :name)
         (vec)))

  (public-declared-method-names java.net.URL)
  (public-declared-method-names java.util.Properties)
  )
