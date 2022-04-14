(ns babashka.impl.reify2.interfaces)

(def interfaces [java.nio.file.FileVisitor
                 java.io.FileFilter
                 java.io.FilenameFilter
                 clojure.lang.Associative
                 clojure.lang.ILookup
                 java.util.Map$Entry
                 clojure.lang.IFn
                 clojure.lang.IPersistentCollection
                 clojure.lang.IReduce
                 clojure.lang.IReduceInit
                 clojure.lang.IKVReduce
                 clojure.lang.Indexed
                 clojure.lang.IPersistentMap
                 clojure.lang.IPersistentStack
                 clojure.lang.Reversible
                 clojure.lang.Seqable
                 java.lang.Iterable
                 java.net.http.WebSocket$Listener
                 java.util.Iterator
                 java.util.function.Consumer
                 java.util.function.Function
                 java.util.function.Predicate
                 java.util.function.Supplier
                 java.lang.Comparable
                 javax.net.ssl.X509TrustManager
                 clojure.lang.LispReader$Resolver])
