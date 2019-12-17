(ns babashka.impl.classes
  {:no-doc true}
  (:require
   [cheshire.core :as json]))

(def classes
  {:default-classes '[clojure.lang.ExceptionInfo
                      clojure.lang.LineNumberingPushbackReader
                      java.io.BufferedReader
                      java.io.BufferedWriter
                      java.io.File
                      java.io.InputStream
                      java.io.OutputStream
                      java.io.StringReader
                      java.io.StringWriter
                      java.lang.ArithmeticException
                      java.lang.AssertionError
                      java.lang.Boolean
                      java.lang.Class
                      java.lang.Double
                      java.lang.Exception
                      java.lang.Integer
                      java.util.concurrent.LinkedBlockingQueue
                      java.lang.String
                      java.lang.System
                      java.lang.Process
                      java.lang.UNIXProcess
                      java.lang.UNIXProcess$ProcessPipeOutputStream
                      java.lang.ProcessBuilder
                      java.lang.ProcessBuilder$Redirect
                      java.nio.file.CopyOption
                      java.nio.file.FileAlreadyExistsException
                      java.nio.file.Files
                      java.nio.file.NoSuchFileException
                      java.nio.file.Path
                      java.nio.file.StandardCopyOption
                      java.nio.file.attribute.FileAttribute
                      java.nio.file.attribute.PosixFilePermission
                      java.nio.file.attribute.PosixFilePermissions
                      java.util.regex.Pattern
                      sun.nio.fs.UnixPath ;; included because of permission check
                      ]
   :custom-classes {'java.lang.Thread
                    ;; generated with `public-declared-method-names`, see in
                    ;; `comment` below
                    {:methods [{:name "activeCount"}
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
                               {:name "yield"}]}}})

(defmacro gen-class-map []
  (let [classes (concat (:default-classes classes)
                        (keys (:custom-classes classes)))]
    (apply hash-map
           (for [c classes
                 c [(list 'quote c) c]]
             c))))

(def class-map (gen-class-map))

#_(defn sym->class-name [sym]
  (-> sym str (str/replace "$" ".")))

(defn generate-reflection-file
  "Generate reflection.json file"
  [& args]
  (let [entries (vec (for [c (sort (:default-classes classes))
                           :let [class-name (str c)]]
                       {:name class-name
                        :allPublicMethods true
                        :allPublicFields true
                        :allPublicConstructors true}))
        custom-entries (for [[c v] (:custom-classes classes)
                             :let [class-name (str c)]]
                         (assoc v :name class-name))
        all-entries (concat entries custom-entries)]
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

  (public-declared-method-names java.lang.UNIXProcess)
  )
