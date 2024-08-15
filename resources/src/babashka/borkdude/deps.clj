(ns borkdude.deps
  "Port of https://github.com/clojure/brew-install/blob/1.11.2/src/main/resources/clojure/install/clojure in Clojure"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   [java.lang ProcessBuilder$Redirect]
   [java.net HttpURLConnection URL URLConnection]
   [java.nio.file CopyOption Files Path]
   [java.util.zip ZipInputStream])
  (:gen-class))

(set! *warn-on-reflection* true)
(def ^:private path-separator (System/getProperty "path.separator"))

;; see https://github.com/clojure/brew-install/blob/1.11.1/CHANGELOG.md
(def ^:private version
  (delay (or (System/getenv "DEPS_CLJ_TOOLS_VERSION")
             "1.11.4.1474")))

(def ^:private cache-version "5")

(def deps-clj-version
  "The current version of deps.clj"
  (or (some-> (io/resource "DEPS_CLJ_VERSION")
              (slurp)
              (str/trim))
      "1.11.4.1474"))

(defn- warn [& strs]
  (binding [*out* *err*]
    (apply println strs)))

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- -debug [& strs]
  (.println System/err
            (with-out-str
              (apply println strs))))

(defn ^:dynamic *exit-fn*
  "Function that is called on exit with `:exit` code and `:message`, an exceptional message when exit is non-zero"
  [{:keys [exit message]}]
  (when message (warn message))
  (System/exit exit))

(def ^:private windows?
  (-> (System/getProperty "os.name")
      (str/lower-case)
      (str/includes? "windows")))

(def ^:dynamic *dir* "Directory in which deps.clj should be executed."
  nil)

(defn- as-string-map
  "Helper to coerce a Clojure map with keyword keys into something coerceable to Map<String,String>
  Stringifies keyword keys, but otherwise doesn't try to do anything clever with values"
  [m]
  (if (map? m)
    (into {} (map (fn [[k v]] [(str (if (keyword? k) (name k) k)) (str v)])) m)
    m))

(defn- add-env
  "Adds environment for a ProcessBuilder instance.
  Returns instance to participate in the thread-first macro."
  ^java.lang.ProcessBuilder [^java.lang.ProcessBuilder pb env]
  (doto (.environment pb)
    (.putAll (as-string-map env)))
  pb)

(defn- set-env
  "Sets environment for a ProcessBuilder instance.
  Returns instance to participate in the thread-first macro."
  ^java.lang.ProcessBuilder [^java.lang.ProcessBuilder pb env]
  (doto (.environment pb)
    (.clear)
    (.putAll (as-string-map env)))
  pb)

(defn- internal-shell-command
  "Executes shell command.

  Accepts the following options:

  `:to-string?`: instead of writing to stdoud, write to a string and
  return it."
  ([args] (internal-shell-command args nil))
  ([args {:keys [out env extra-env]}]
   (let [to-string? (= :string out)
         args (mapv str args)
         args (if (and windows? (not (System/getenv "DEPS_CLJ_NO_WINDOWS_FIXES")))
                (mapv #(str/replace % "\"" "\\\"") args)
                args)
         pb (cond-> (ProcessBuilder. ^java.util.List args)
              true (.redirectError ProcessBuilder$Redirect/INHERIT)
              (not to-string?) (.redirectOutput ProcessBuilder$Redirect/INHERIT)
              true (.redirectInput ProcessBuilder$Redirect/INHERIT))
         _ (when-let [dir *dir*]
             (.directory pb (io/file dir)))
         _ (when-let [env env]
             (set-env pb env))
         _ (when-let [extra-env extra-env]
             (add-env pb extra-env))
         proc (.start pb)
         string-out
         (when to-string?
           (let [sw (java.io.StringWriter.)]
             (with-open [w (io/reader (.getInputStream proc))]
               (io/copy w sw))
             (str sw)))
         exit-code (.waitFor proc)]
     (when (not (zero? exit-code))
       (*exit-fn* {:exit exit-code}))
     {:out string-out
      :exit exit-code})))

(defn ^:dynamic *aux-process-fn*
  "Invokes `java` with arguments to calculate classpath, etc. May be
  replaced by rebinding this dynamic var.

  Called with a map of:

  - `:cmd`: a vector of strings
  - `:out`: if set to `:string`, `:out` key in result must contains stdout

  Returns a map of:

  - `:exit`, the exit code of the process
  - `:out`, the string of stdout, if the input `:out` was set to `:string`"
  [{:keys [cmd out]}]
  (internal-shell-command cmd {:out out}))

(defn ^:dynamic *clojure-process-fn*
  "Invokes `java` with arguments to `clojure.main` to start Clojure. May
  be replaced by rebinding this dynamic var.

  Called with a map of:

  - `:cmd`: a vector of strings

  Must return a map of `:exit`, the exit code of the process."
  [{:keys [cmd]}]
  (internal-shell-command cmd))

(def ^:private help-text (delay (str "Version: " @version "

You use the Clojure tools ('clj' or 'clojure') to run Clojure programs
on the JVM, e.g. to start a REPL or invoke a specific function with data.
The Clojure tools will configure the JVM process by defining a classpath
(of desired libraries), an execution environment (JVM options) and
specifying a main class and args.

Using a deps.edn file (or files), you tell Clojure where your source code
resides and what libraries you need. Clojure will then calculate the full
set of required libraries and a classpath, caching expensive parts of this
process for better performance.

The internal steps of the Clojure tools, as well as the Clojure functions
you intend to run, are parameterized by data structures, often maps. Shell
command lines are not optimized for passing nested data, so instead you
will put the data structures in your deps.edn file and refer to them on the
command line via 'aliases' - keywords that name data structures.

'clj' and 'clojure' differ in that 'clj' has extra support for use as a REPL
in a terminal, and should be preferred unless you don't want that support,
then use 'clojure'.

Usage:
  Start a REPL  clj     [clj-opt*] [-Aaliases]
  Exec fn(s)    clojure [clj-opt*] -X[aliases] [a/fn*] [kpath v]*
  Run main      clojure [clj-opt*] -M[aliases] [init-opt*] [main-opt] [arg*]
  Run tool      clojure [clj-opt*] -T[name|aliases] a/fn [kpath v] kv-map?
  Prepare       clojure [clj-opt*] -P [other exec opts]

exec-opts:
 -Aaliases      Use concatenated aliases to modify classpath
 -X[aliases]    Use concatenated aliases to modify classpath or supply exec fn/args
 -M[aliases]    Use concatenated aliases to modify classpath or supply main opts
 -P             Prepare deps - download libs, cache classpath, but don't exec

clj-opts:
 -Jopt          Pass opt through in java_opts, ex: -J-Xmx512m
 -Sdeps EDN     Deps data to use as the last deps file to be merged
 -Spath         Compute classpath and echo to stdout only
 -Stree         Print dependency tree
 -Scp CP        Do NOT compute or cache classpath, use this one instead
 -Srepro        Ignore the ~/.clojure/deps.edn config file
 -Sforce        Force recomputation of the classpath (don't use the cache)
 -Sverbose      Print important path info to console
 -Sdescribe     Print environment and command parsing info as data
 -Sthreads      Set specific number of download threads
 -Strace        Write a trace.edn file that traces deps expansion
 --             Stop parsing dep options and pass remaining arguments to clojure.main
 --version      Print the version to stdout and exit
 -version       Print the version to stdout and exit

The following non-standard options are available only in deps.clj:

 -Sdeps-file    Use this file instead of deps.edn
 -Scommand      A custom command that will be invoked. Substitutions: {{classpath}}, {{main-opts}}.

init-opt:
 -i, --init path     Load a file or resource
 -e, --eval string   Eval exprs in string; print non-nil values
 --report target     Report uncaught exception to \"file\" (default), \"stderr\", or \"none\"

main-opt:
 -m, --main ns-name  Call the -main function from namespace w/args
 -r, --repl          Run a repl
 path                Run a script from a file or resource
 -                   Run a script from standard input
 -h, -?, --help      Print this help message and exit

Programs provided by :deps alias:
 -X:deps aliases           List available aliases and their source
 -X:deps list              List full transitive deps set and licenses
 -X:deps tree              Print deps tree
 -X:deps find-versions     Find available versions of a library
 -X:deps prep              Prepare all unprepped libs in the dep tree
 -X:deps mvn-pom           Generate (or update) pom.xml with deps and paths
 -X:deps mvn-install       Install a maven jar to the local repository cache
 -X:deps git-resolve-tags  Resolve git coord tags to shas and update deps.edn

For more info, see:
 https://clojure.org/guides/deps_and_cli
 https://clojure.org/reference/repl_and_main")))

(defn- describe-line [[kw val]]
  (pr kw val))

(defn- describe [lines]
  (let [[first-line & lines] lines]
    (print "{") (describe-line first-line)
    (doseq [line lines
            :when line]
      (print "\n ") (describe-line line))
    (println "}")))

(defn ^:dynamic *getenv-fn*
  "Get ENV'ironment variable, typically used for getting `CLJ_CONFIG`, etc."
  ^String [env]
  (java.lang.System/getenv env))

(defn- cksum
  [^String s]
  (let [hashed (.digest (java.security.MessageDigest/getInstance "MD5")
                        (.getBytes s))
        sw (java.io.StringWriter.)]
    (binding [*out* sw]
      (doseq [byte hashed]
        (print (format "%02X" byte))))
    (str sw)))

(defn- which [executable]
  (when-let [path (*getenv-fn* "PATH")]
    (let [paths (.split path path-separator)]
      (loop [paths paths]
        (when-first [p paths]
          (let [f (io/file p executable)]
            (if (and (.isFile f)
                     (.canExecute f))
              (.getCanonicalPath f)
              (recur (rest paths)))))))))

(defn- home-dir []
  (if windows?
    (or (*getenv-fn* "HOME")
        (*getenv-fn* "USERPROFILE"))
    (*getenv-fn* "HOME")))

(def ^:private java-exe (if windows? "java.exe" "java"))

(defn- get-java-cmd
  "Returns path to java executable to invoke sub commands with."
  []
  (or (*getenv-fn* "JAVA_CMD")
      (let [java-cmd (which java-exe)]
        (if (str/blank? java-cmd)
          (let [java-home (*getenv-fn* "JAVA_HOME")]
            (if-not (str/blank? java-home)
              (let [f (io/file java-home "bin" java-exe)]
                (if (and (.exists f)
                         (.canExecute f))
                  (.getCanonicalPath f)
                  (throw (Exception. "Couldn't find 'java'. Please set JAVA_HOME."))))
              (throw (Exception. "Couldn't find 'java'. Please set JAVA_HOME."))))
          java-cmd))))

(def ^:private authenticated-proxy-re #".+:.+@(.+):(\d+).*")
(def ^:private unauthenticated-proxy-re #"(.+):(\d+).*")

(defn- proxy-info [m]
  {:host (nth m 1)
   :port (nth m 2)})

(defn- parse-proxy-info
  [s]
  (when s
    (let [p (cond
              (str/starts-with? s "http://") (subs s 7)
              (str/starts-with? s "https://") (subs s 8)
              :else s)
          auth-proxy-match (re-matches authenticated-proxy-re p)
          unauth-proxy-match (re-matches unauthenticated-proxy-re p)]
      (cond
        auth-proxy-match
        (do (warn "WARNING: Proxy info is of authenticated type - discarding the user/pw as we do not support it!")
            (proxy-info auth-proxy-match))

        unauth-proxy-match
        (proxy-info unauth-proxy-match)

        :else
        (do (warn "WARNING: Can't parse proxy info - found:" s "- proceeding without using proxy!")
            nil)))))

(defn get-proxy-info
  "Returns a map with proxy information parsed from env vars. The map
   will contain :http-proxy and :https-proxy entries if the relevant
   env vars are set and parsed correctly. The value for each is a map
   with :host and :port entries."
  []
  (let [http-proxy (parse-proxy-info (or (*getenv-fn* "http_proxy")
                                         (*getenv-fn* "HTTP_PROXY")))
        https-proxy (parse-proxy-info (or (*getenv-fn* "https_proxy")
                                          (*getenv-fn* "HTTPS_PROXY")))]
    (cond-> {}
      http-proxy (assoc :http-proxy http-proxy)
      https-proxy (assoc :https-proxy https-proxy))))

(defn set-proxy-system-props!
  "Sets the proxy system properties in the current JVM.
   proxy-info parameter is as returned from `get-proxy-info.`"
  [{:keys [http-proxy https-proxy]}]
  (when http-proxy
    (System/setProperty "http.proxyHost" (:host http-proxy))
    (System/setProperty "http.proxyPort" (:port http-proxy)))
  (when https-proxy
    (System/setProperty "https.proxyHost" (:host https-proxy))
    (System/setProperty "https.proxyPort" (:port https-proxy))))

(defn clojure-tools-download-direct!
  "Downloads from `:url` to `:dest` file returning true on success."
  [{:keys [url dest]}]
  (try
    (set-proxy-system-props! (get-proxy-info))
    (let [source (URL. url)
          dest (io/file dest)
          conn ^URLConnection (.openConnection ^URL source)]
      (when (instance? HttpURLConnection conn)
        (.setInstanceFollowRedirects ^java.net.HttpURLConnection conn true))
      (.connect conn)
      (with-open [is (.getInputStream conn)]
        (io/copy is dest))
      true)
    (catch Exception e
      (warn ::direct-download (.getMessage e))
      false)))

;; https://github.com/clojure/brew-install/releases/download/1.11.1.1386/clojure-tools.zip

(def ^:private clojure-tools-info*
  "A delay'd map with information about the clojure tools archive, where
  to download it from, which files to extract and where to.

  The map contains:

  :ct-base-dir The relative top dir name to extract the archive files to.

  :ct-error-exit-code The process exit code to return if the archive
  cannot be downloaded.

  :ct-aux-files-names Other important files in the archive.

  :ct-jar-name The main clojure tools jar file in the archive.

  :ct-url-str The url to download the archive from.

  :ct-zip-name The file name to store the archive as."
  (delay (let [version @version
               commit (-> (str/split version #"\.")
                          last
                          (Long/parseLong))
               github-release? (>= commit 1386)
               verify-sha256 (>= commit 1403)
               url (if github-release?
                     (format "https://github.com/clojure/brew-install/releases/download/%s/clojure-tools.zip" version)
                     (format "https://download.clojure.org/install/clojure-tools-%s.zip" version))]
           {:ct-base-dir "ClojureTools"
            :ct-error-exit-code 99
            :ct-aux-files-names ["exec.jar" "example-deps.edn" "tools.edn"]
            :ct-jar-name (format "clojure-tools-%s.jar" version)
            :ct-url-str url
            :ct-zip-name "clojure-tools.zip"
            :sha256-url-str (when verify-sha256
                              (str url ".sha256"))})))

(def zip-invalid-msg
  (str/join \n
            ["The tools zip file may have not been succesfully downloaded."
             "Please report this problem and keep a backup of the tools zip file as a repro."
             "You can try again by removing the $HOME/.deps.clj folder."]))

(defn- unzip
  [zip-file destination-dir]
  (let [transaction-file (io/file destination-dir "TRANSACTION_START")
        {:keys [ct-aux-files-names ct-jar-name]} @clojure-tools-info*
        zip-file (io/file zip-file)
        destination-dir (io/file destination-dir)
        _ (.mkdirs destination-dir)
        destination-dir (.toPath destination-dir)
        zip-file (.toPath zip-file)
        files (into #{ct-jar-name} ct-aux-files-names)]
    (spit transaction-file "")
    (with-open
     [fis (Files/newInputStream zip-file (into-array java.nio.file.OpenOption []))
      zis (ZipInputStream. fis)]
      (loop [to-unzip files]
        (if-let [entry (.getNextEntry zis)]
          (let [entry-name (.getName entry)
                cis (java.util.zip.CheckedInputStream. zis (java.util.zip.CRC32.))
                file-name (.getName (io/file entry-name))]
            (if (contains? files file-name)
              (let [new-path (.resolve destination-dir file-name)]
                (Files/copy ^java.io.InputStream cis
                            new-path
                            ^"[Ljava.nio.file.CopyOption;"
                            (into-array CopyOption
                                        [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
                (let [bytes (Files/readAllBytes new-path)
                      crc (java.util.zip.CRC32.)
                      _ (.update crc bytes)
                      file-crc (.getValue crc)]
                  (when-not (= file-crc (.getCrc entry) (-> cis (.getChecksum) (.getValue)))
                    (let [msg (str "CRC check failed when unzipping zip-file " zip-file ", entry: " entry-name)]
                      (warn msg)
                      (warn zip-invalid-msg)
                      (*exit-fn* {:exit 1 :message msg}))))
                (recur (disj to-unzip file-name)))
              (recur to-unzip)))
          (when-not (empty? to-unzip)
            (let [msg (str zip-file " did not contain all of the expected files, missing: " (str/join " " to-unzip))]
              (warn msg)
              (warn zip-invalid-msg)
              (*exit-fn* {:exit 1 :message msg}))))))))

(defn- clojure-tools-java-downloader-spit
  "Spits out and returns the path to `ClojureToolsDownloader.java` file
  in DEST-DIR'ectory that when invoked (presumambly by the `java`
  executable directly) with a source URL and destination file path in
  args[0] and args[1] respectively, will download the source to
  destination. No arguments validation is performed and returns exit
  code 1 on failure."
  [dest-dir]
  (let [dest-file (.getCanonicalPath (io/file dest-dir "ClojureToolsDownloader.java"))]
    (spit dest-file
          (str "
/** Auto-generated by " *file* ". **/
package borkdude.deps;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLConnection;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.Channels;import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
public class ClojureToolsDownloader {
    public static void main (String[] args) {
    try {
        URL url = new URL(args[0]);
//        System.err.println (\":0 \" +args [0]+ \" :1 \"+args [1]);
        URLConnection conn = url.openConnection();
        if (conn instanceof HttpURLConnection)
           {((HttpURLConnection) conn).setInstanceFollowRedirects(true);}
        ReadableByteChannel readableByteChannel = Channels.newChannel(conn.getInputStream());
        FileOutputStream fileOutputStream = new FileOutputStream(args[1]);
        FileChannel fileChannel = fileOutputStream.getChannel();
        fileOutputStream.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
        fileOutputStream.close();
        fileChannel.close();
        System.exit(0);
    } catch (IOException e) {
        e.printStackTrace();
        System.exit(1); }}}"))
    dest-file))

(defn proxy-jvm-opts
  "Returns a vector containing the JVM system property arguments to be passed to a new process
   to set its proxy system properties.
   proxy-info parameter is as returned from `get-proxy-info.`"
  [{:keys [http-proxy https-proxy]}]
  (cond-> []
    http-proxy (concat [(str "-Dhttp.proxyHost=" (:host http-proxy))
                        (str "-Dhttp.proxyPort=" (:port http-proxy))])
    https-proxy (concat [(str "-Dhttps.proxyHost=" (:host https-proxy))
                         (str "-Dhttps.proxyPort=" (:port https-proxy))])))

(defn clojure-tools-download-java!
  "Downloads `:url` zip file to `:dest` by invoking `java` with
  `:proxy` options on a `.java` program file, and returns true on
  success. Requires Java 11+ (JEP 330)."
  [{:keys [url dest proxy-opts clj-jvm-opts sha256-url]}]
  (let [dest-dir (.getCanonicalPath (io/file dest ".."))
        dlr-path (clojure-tools-java-downloader-spit dest-dir)
        java-cmd [(get-java-cmd) "-XX:-OmitStackTraceInFastThrow"]
        success?* (atom true)]
    (binding [*exit-fn* (fn [{:keys [exit message]}]
                          (when-not (= exit 0)
                            (warn message)
                            (reset! success?* false)))]
      (*aux-process-fn* {:cmd (vec (concat java-cmd
                                           clj-jvm-opts
                                           (proxy-jvm-opts proxy-opts)
                                           [dlr-path url (str dest)]))})
      (when sha256-url
        (*aux-process-fn* {:cmd (vec (concat java-cmd
                                             clj-jvm-opts
                                             (proxy-jvm-opts proxy-opts)
                                             [dlr-path sha256-url (str dest ".sha256")]))}))
      (io/delete-file dlr-path true)
      @success?*)))


(def ^:dynamic *clojure-tools-download-fn*
  "Can be dynamically rebound to customise the download of the Clojure tools.
   Should be bound to a function accepting a map with:
   - `:url`: The URL to download, as a string
   - `:dest`: The path to the file to download it to, as a string
   - `:proxy-opts`: a map as returned by `get-proxy-info`
   - `:clj-jvm-opts`: a vector of JVM opts (as passed on the command line).

  Should return `true` if the download was successful, or false if not."
  nil)

(defn- left-pad-zeroes [s n]
  (str (str/join (repeat (- n (count s)) 0)) s))

(defn clojure-tools-install!
  "Installs clojure tools archive by downloading it in `:out-dir`, if not already there,
  and extracting in-place.

  If `*clojure-tools-download-fn*` is set, it will be called for
  download the tools archive. This function should return a truthy
  value to indicate a successful download.

  The download is attempted directly from this process, unless
  `:clj-jvm-opts` is set, in which case a java subprocess
  is created to download the archive passing in its value as command
  line options.

  It calls `*exit-fn*` if it cannot download the archive, with
  instructions how to manually download it."
  [{:keys [out-dir debug proxy-opts clj-jvm-opts config-dir]}]
  (let [{:keys [ct-error-exit-code ct-url-str ct-zip-name sha256-url-str]} @clojure-tools-info*
        dir (io/file out-dir)
        zip-file (io/file out-dir ct-zip-name)
        sha256-file (io/file (str zip-file ".sha256"))
        transaction-start (io/file out-dir "TRANSACTION_START")]
    (io/make-parents transaction-start)
    (spit transaction-start "")
    (when-not (.exists zip-file)
      (warn "Downloading" ct-url-str "to" (str zip-file))
      (let [res (or (when *clojure-tools-download-fn*
                      (when debug (warn "Attempting download using custom download function..."))
                      (*clojure-tools-download-fn* {:url ct-url-str :dest (str zip-file) :proxy-opts proxy-opts :clj-jvm-opts clj-jvm-opts :sha256-url sha256-url-str}))
                    (when (seq clj-jvm-opts)
                      (when debug (warn "Attempting download using java subprocess... (requires Java11+)"))
                      (clojure-tools-download-java! {:url ct-url-str :dest (str zip-file) :proxy-opts proxy-opts :clj-jvm-opts clj-jvm-opts :sha256-url sha256-url-str}))
                    (do (when debug (warn "Attempting direct download..."))
                        (let [res (clojure-tools-download-direct! {:url ct-url-str :dest zip-file})]
                          (when sha256-url-str
                            (clojure-tools-download-direct! {:url sha256-url-str :dest (str zip-file ".sha256")}))
                          res))
                    (*exit-fn* {:exit ct-error-exit-code
                                :message (str "Error: Cannot download Clojure tools."
                                              " Please download manually from " ct-url-str
                                              " to " (str (io/file dir ct-zip-name)))})
                    ::passthrough)]
        (when (and sha256-url-str (not *clojure-tools-download-fn*) (not (.exists sha256-file)) (not= ::passthrough res))
          (*exit-fn* {:exit ct-error-exit-code
                      :message (str "Expected sha256 file to be downloaded to: " sha256-file)}))))
    (when (.exists sha256-file)
      (let [sha (-> (slurp sha256-file)
                    str/trim
                    (left-pad-zeroes 64))
            bytes (Files/readAllBytes (.toPath zip-file))
            hash (-> (java.security.MessageDigest/getInstance "SHA-256")
                     (.digest bytes))
            hash (-> (new BigInteger 1 hash)
                     (.toString 16))
            hash (left-pad-zeroes hash 64)]
        (if-not (= sha hash)
          (*exit-fn* {:exit ct-error-exit-code
                      :message (str "Error: sha256 of zip and expected sha256 do not match: "
                                    hash " vs. " sha "\n"
                                    " Please download manually from " ct-url-str
                                    " to " (str (io/file dir ct-zip-name)))})
          (.delete sha256-file))))
    (warn "Unzipping" (str zip-file) "...")
    (unzip zip-file (.getPath dir))
    (.delete zip-file)
    (when config-dir
      (let [config-deps-edn (io/file config-dir "deps.edn")
            example-deps-edn (io/file out-dir "example-deps.edn")]
        (when (and (not (.exists config-deps-edn))
                   (.exists example-deps-edn))
          (io/make-parents config-deps-edn)
          (io/copy example-deps-edn config-deps-edn)))
      (let [config-tools-edn (io/file config-dir "tools" "tools.edn")
            install-tools-edn (io/file out-dir "tools.edn")]
        (when (and (not (.exists config-tools-edn))
                   (.exists install-tools-edn))
          (io/make-parents config-tools-edn)
          (io/copy install-tools-edn config-tools-edn))))
    ;; Successful transaction
    (.delete transaction-start))
  (warn "Successfully installed clojure tools!"))

(def ^:private parse-opts->keyword
  {"-J" :jvm-opts
   "-R" :resolve-aliases
   "-C" :classpath-aliases
   "-A" :repl-aliases})

(def ^:private bool-opts->keyword
  {"-Spath" :print-classpath
   "-Sverbose" :verbose
   "-Strace" :trace
   "-Sdescribe" :describe
   "-Sforce" :force
   "-Srepro" :repro
   "-Stree" :tree
   "-Spom" :pom
   "-P" :prep})

(def ^:private string-opts->keyword
  {"-Sdeps" :deps-data
   "-Scp" :force-cp
   "-Sdeps-file" :deps-file
   "-Scommand" :command
   "-Sthreads" :threads})

(defn ^:private non-blank [s]
  (when-not (str/blank? s)
    s))

(def ^:private vconj (fnil conj []))

(defn parse-cli-opts
  "Parses the command line options."
  [args]
  (loop [args (seq args)
         acc {:mode :repl}]
    (if args
      (let [arg (first args)
            [arg args]
            ;; workaround for Powershell, see GH-42
            (if (and windows? (#{"-X:" "-M:" "-A:" "-T:"} arg))
              [(str arg (second args))
               (next args)]
              [arg args])
            bool-opt-keyword (get bool-opts->keyword arg)
            string-opt-keyword (get string-opts->keyword arg)]
        (cond
          (= "--" arg) (assoc acc :args (next args))
          (or (= "-version" arg)
              (= "--version" arg)) (assoc acc :version true)
          (str/starts-with? arg "-M")
          (assoc acc
                 :mode :main
                 :main-aliases (non-blank (subs arg 2))
                 :args (next args))
          (str/starts-with? arg "-X")
          (assoc acc
                 :mode :exec
                 :exec-aliases (non-blank (subs arg 2))
                 :args (next args))
          (str/starts-with? arg "-T:")
          (assoc acc
                 :mode :tool
                 :tool-aliases (non-blank (subs arg 2))
                 :args (next args))
          (str/starts-with? arg "-T")
          (assoc acc
                 :mode :tool
                 :tool-name (non-blank (subs arg 2))
                 :args (next args))
          ;; deprecations
          (some #(str/starts-with? arg %) ["-R" "-C"])
          (do (warn arg "-R is no longer supported, use -A with repl, -M for main, -X for exec, -T for tool")
              (*exit-fn* {:exit 1}))
          (some #(str/starts-with? arg %) ["-O"])
          (let [msg (str arg " is no longer supported, use -A with repl, -M for main, -X for exec, -T for tool")]
            (*exit-fn* {:exit 1 :message msg}))
          (= "-Sresolve-tags" arg)
          (let [msg "Option changed, use: clj -X:deps git-resolve-tags"]
            (*exit-fn* {:exit 1 :message msg}))
          ;; end deprecations
          (= "-A" arg)
          (let [msg "-A requires an alias"]
            (*exit-fn* {:exit 1 :message msg}))
          (some #(str/starts-with? arg %) ["-J" "-C" "-O" "-A"])
          (recur (next args)
                 (update acc (get parse-opts->keyword (subs arg 0 2))
                         vconj (non-blank (subs arg 2))))
          bool-opt-keyword (recur
                            (next args)
                            (assoc acc bool-opt-keyword true))
          string-opt-keyword (recur
                              (nnext args)
                              (assoc acc string-opt-keyword
                                     (second args)))
          (str/starts-with? arg "-S") (let [msg (str "Invalid option: " arg)]
                                        (*exit-fn* {:exit 1 :message msg}))
          (and
           (not (some acc [:main-aliases :all-aliases]))
           (or (= "-h" arg)
               (= "--help" arg))) (assoc acc :help true)
          :else (assoc acc :args args)))
      acc)))


(defn- as-path
  ^Path [path]
  (if (instance? Path path) path
      (.toPath (io/file path))))

(defn- unixify
  ^Path [f]
  (as-path (if windows?
             (-> f as-path .toUri .getPath)
             (str f))))

(defn- relativize
  "Returns relative path as string by comparing this with other. Returns
  absolute path unchanged."
  ^Path [f]
  (str (if (.isAbsolute (as-path f))
         f
         (if-let [dir *dir*]
           (if-not (.getParent (io/file (str f)))
             ;; workaround for https://github.com/babashka/bbin/issues/80
             ;; when f is a single segment file but the current working directory is on a different disk than *dir*
             (as-path f)
             (.relativize (unixify (.toAbsolutePath (as-path dir)))
                          (unixify (.toAbsolutePath (as-path f)))))
           f))))

(defn- resolve-in-dir
  "Resolves against directory (when provided). Absolute paths are unchanged.
  Returns string."
  [dir path]
  (if dir
    (str (.resolve (as-path dir) (str path)))
    (str path)))

(defn- get-env-tools-dir
  "Retrieves the tools-directory from environment variable `DEPS_CLJ_TOOLS_DIR`"
  []
  (or
    ;; legacy name
   (*getenv-fn* "CLOJURE_TOOLS_DIR")
   (*getenv-fn* "DEPS_CLJ_TOOLS_DIR")))

(defn get-install-dir
  "Retrieves the install directory where tools jar is located (after download).
  Defaults to ~/.deps.clj/<version>/ClojureTools."
  []
  (let [{:keys [ct-base-dir]} @clojure-tools-info*]
    (or (get-env-tools-dir)
        (.getPath (io/file (home-dir)
                           ".deps.clj"
                           @version
                           ct-base-dir)))))

(defn get-config-dir
  "Retrieves configuration directory.
  First tries `CLJ_CONFIG` env var, then `$XDG_CONFIG_HOME/clojure`, then ~/.clojure."
  []
  (or (*getenv-fn* "CLJ_CONFIG")
      (when-let [xdg-config-home (*getenv-fn* "XDG_CONFIG_HOME")]
        (.getPath (io/file xdg-config-home "clojure")))
      (.getPath (io/file (home-dir) ".clojure"))))

(defn get-local-deps-edn
  "Returns the path of the `deps.edn` file (as string) in the current directory or as set by `-Sdeps-file`.
  Required options:
  * `:cli-opts`: command line options as parsed by `parse-opts`"
  [{:keys [cli-opts]}]
  (or (:deps-file cli-opts)
      (.getPath (io/file *dir* "deps.edn"))))

(defn get-cache-dir*
  "Returns `:cache-dir` (`.cpcache`) and `:cache-dir-key` from either
  local dir, if `deps-edn` exists, or the user cache dir. The
  `:cache-dir-key` is used in case the working directory isn't
  writable and the cache must be stored in the user-cache-dir."
  [{:keys [deps-edn config-dir]}]
  (let [user-cache-dir
        (or (*getenv-fn* "CLJ_CACHE")
            (when-let [xdg-config-home (*getenv-fn* "XDG_CACHE_HOME")]
              (.getPath (io/file xdg-config-home "clojure")))
            (.getPath (io/file config-dir ".cpcache")))]
    (if (.exists (io/file deps-edn))
      (if (-> (io/file (or *dir* ".")) (.toPath) (java.nio.file.Files/isWritable))
        {:cache-dir (.getPath (io/file *dir* ".cpcache"))
         :cache-dir-key *dir*}
        ;; can't write to *dir*/.cpcache
        {:cache-dir user-cache-dir})
      {:cache-dir user-cache-dir})))

(defn get-cache-dir
  "Returns cache dir (`.cpcache`) from either local dir, if `deps-edn`
  exists, or the user cache dir.
  DEPRECATED: use `get-cache-dir*` instead."
  {:deprecated "use get-cache-dir* instead"}
  [m]
  (:cache-dir (get-cache-dir* m)))

(defn get-config-paths
  "Returns vec of configuration paths, i.e. deps.edn from:
  - `:install-dir` as obtained thrhough `get-install-dir`
  - `:config-dir` as obtained through `get-config-dir`
  - `:deps-edn` as obtained through `get-local-deps-edn`"
  [{:keys [cli-opts deps-edn config-dir install-dir]}]
  (if (:repro cli-opts)
    (if install-dir
      [(.getPath (io/file install-dir "deps.edn")) deps-edn]
      [deps-edn])
    (if install-dir
      [(.getPath (io/file install-dir "deps.edn"))
       (.getPath (io/file config-dir "deps.edn"))
       deps-edn]
      [(.getPath (io/file config-dir "deps.edn"))
       deps-edn])))

(defn get-checksum
  "Returns checksum based on cli-opts (as returned by `parse-cli-opts`)
  and config-paths (as returned by `get-config-paths`)"
  [{:keys [cli-opts config-paths cache-dir-key]}]
  (let [val*
        (str/join "|"
                  (concat (cond-> [cache-version]
                            cache-dir-key (conj cache-dir-key))
                          (:repl-aliases cli-opts)
                          [(:exec-aliases cli-opts)
                           (:main-aliases cli-opts)
                           (:deps-data cli-opts)
                           (:tool-name cli-opts)
                           (:tool-aliases cli-opts)]
                          (map (fn [config-path]
                                 (if (.exists (io/file config-path))
                                   config-path
                                   "NIL"))
                               config-paths)))]
    (cksum val*)))

(defn get-help
  "Returns help text as string."
  []
  @help-text)

(defn print-help
  "Print help text"
  []
  (println @help-text))

(defn get-basis-file
  "Returns path to basis file. Required options:

  * - `cache-dir` as returned by `get-cache-dir`
  * - `checksum` as returned by `get-check-sum`"
  [{:keys [cache-dir checksum]}]
  (.getPath (io/file cache-dir (str checksum ".basis"))))

(defn- auto-file-arg [cp]
  ;; see https://devblogs.microsoft.com/oldnewthing/20031210-00/?p=41553
  ;; command line limit on Windows with process builder
  (if (and windows? (> (count cp) 32766))
    (let [tmp-file (.toFile (java.nio.file.Files/createTempFile
                             "file_arg" ".txt"
                             (into-array java.nio.file.attribute.FileAttribute [])))]
      (.deleteOnExit tmp-file)
      ;; we use pr-str since whitespaces in the classpath will be treated as separate args otherwise
      (spit tmp-file (pr-str cp))
      (str "@" tmp-file))
    cp))

(defn -main
  "See `help-text`.

  In addition

  - the values of the `CLJ_JVM_OPTS` and `JAVA_OPTIONS` environment
  variables are passed to the java subprocess as command line options
  when downloading dependencies and running any other commands
  respectively.

  - if the clojure tools jar cannot be located and the clojure tools
  archive is not found, an attempt is made to download the archive
  from the official site and extract its contents locally. The archive
  is downloaded from this process directly, unless the `CLJ_JVM_OPTS`
  env variable is set and a succesful attempt is made to download the
  archive by invoking a java subprocess passing the env variable value
  as command line options."
  [& command-line-args]
  (let [cli-opts (parse-cli-opts command-line-args)
        {:keys [ct-jar-name]} @clojure-tools-info*
        debug (*getenv-fn* "DEPS_CLJ_DEBUG")
        java-cmd [(get-java-cmd) "-XX:-OmitStackTraceInFastThrow"]
        env-tools-dir (get-env-tools-dir)
        install-dir (get-install-dir)
        libexec-dir (if env-tools-dir
                      (let [f (io/file env-tools-dir "libexec")]
                        (if (.exists f)
                          (.getPath f)
                          env-tools-dir))
                      install-dir)
        tools-jar (io/file libexec-dir ct-jar-name)
        exec-jar (io/file libexec-dir "exec.jar")
        proxy-opts (get-proxy-info)
        proxy-settings (proxy-jvm-opts proxy-opts)
        clj-jvm-opts (some-> (*getenv-fn* "CLJ_JVM_OPTS") (str/split #" "))
        config-dir (get-config-dir)
        tools-cp
        (or
         (when (and (.exists tools-jar)
                    ;; aborted transaction
                    (not (.exists (io/file libexec-dir "TRANSACTION_START"))))
           (.getPath tools-jar))
         (binding [*out* *err*]
           (warn "Clojure tools not yet in expected location:" (str tools-jar))
           (clojure-tools-install! {:out-dir libexec-dir :debug debug :clj-jvm-opts clj-jvm-opts :proxy-opts proxy-opts :config-dir config-dir})
           tools-jar))
        mode (:mode cli-opts)
        exec? (= :exec mode)
        tool? (= :tool mode)
        exec-cp (when (or exec? tool?)
                  (.getPath exec-jar))
        deps-edn (get-local-deps-edn {:cli-opts cli-opts})
        clj-main-cmd
        (vec (concat java-cmd
                     clj-jvm-opts
                     proxy-settings
                     ["-classpath" tools-cp "clojure.main"]))
        java-opts (some-> (*getenv-fn* "JAVA_OPTS") (str/split #" "))]
    ;; If user config directory does not exist, create it
    (let [config-dir (io/file config-dir)]
      (when-not (.exists config-dir)
        (.mkdirs config-dir)))
    (let [config-deps-edn (io/file config-dir "deps.edn")
          example-deps-edn (io/file install-dir "example-deps.edn")]
      (when (and install-dir
                 (not (.exists config-deps-edn))
                 (.exists example-deps-edn))
        (io/copy example-deps-edn config-deps-edn)))
    (let [config-tools-edn (io/file config-dir "tools" "tools.edn")
          install-tools-edn (io/file install-dir "tools.edn")]
      (when (and install-dir
                 (not (.exists config-tools-edn))
                 (.exists install-tools-edn))
        (io/make-parents config-tools-edn)
        (io/copy install-tools-edn config-tools-edn)))
    ;; Determine user cache directory
    (let [;; Chain deps.edn in config paths. repro=skip config dir
          config-user
          (when-not (:repro cli-opts)
            (.getPath (io/file config-dir "deps.edn")))
          config-project deps-edn
          config-paths (get-config-paths {:cli-opts cli-opts
                                          :deps-edn deps-edn
                                          :config-dir config-dir
                                          :install-dir install-dir})
          {:keys [cache-dir cache-dir-key]}
          (get-cache-dir* {:deps-edn deps-edn :config-dir config-dir})
          ;; Construct location of cached classpath file
          tool-name (:tool-name cli-opts)
          tool-aliases (:tool-aliases cli-opts)
          ck (get-checksum {:cli-opts cli-opts :config-paths config-paths
                            :cache-dir-key cache-dir-key})
          cp-file (.getPath (io/file cache-dir (str ck ".cp")))
          jvm-file (.getPath (io/file cache-dir (str ck ".jvm")))
          main-file (.getPath (io/file cache-dir (str ck ".main")))
          basis-file (get-basis-file {:cache-dir cache-dir :checksum ck})
          manifest-file (.getPath (io/file cache-dir (str ck ".manifest")))
          _ (when (:verbose cli-opts)
              (println "deps.clj version =" deps-clj-version)
              (println "version          =" @version)
              (when install-dir (println "install_dir      =" install-dir))
              (println "config_dir       =" config-dir)
              (println "config_paths     =" (str/join " " config-paths))
              (println "cache_dir        =" cache-dir)
              (println "cp_file          =" cp-file)
              (println))
          tree? (:tree cli-opts)
          ;; Check for stale classpath file
          cp-file (io/file cp-file)
          stale
          (or (:force cli-opts)
              (:trace cli-opts)
              tree?
              (:prep cli-opts)
              (not (.exists cp-file))
              (when tool-name
                (let [tool-file (io/file config-dir "tools" (str tool-name ".edn"))]
                  (when (.exists tool-file)
                    (> (.lastModified tool-file)
                       (.lastModified cp-file)))))
              (some (fn [config-path]
                      (let [f (io/file config-path)]
                        (when (.exists f)
                          (> (.lastModified f)
                             (.lastModified cp-file))))) config-paths)
              ;; Are deps.edn files stale?
              (when (.exists (io/file manifest-file))
                (let [manifests (-> manifest-file slurp str/split-lines)]
                  (some (fn [manifest]
                          (let [f (io/file manifest)]
                            (or (not (.exists f))
                                (> (.lastModified f)
                                   (.lastModified cp-file))))) manifests)))
              ;; Are .jar files in classpath missing?
              (let [cp (slurp cp-file)
                    entries (vec (.split ^String cp java.io.File/pathSeparator))]
                (some (fn [entry]
                        (when (str/ends-with? entry ".jar")
                          (not (.exists (io/file (resolve-in-dir *dir* entry))))))
                      entries)))
          tools-args
          (when (or stale (:pom cli-opts))
            (cond-> []
              (not (str/blank? (:deps-data cli-opts)))
              (conj "--config-data" (:deps-data cli-opts))
              (:main-aliases cli-opts)
              (conj (str "-M" (:main-aliases cli-opts)))
              (:repl-aliases cli-opts)
              (conj (str "-A" (str/join (:repl-aliases cli-opts))))
              (:exec-aliases cli-opts)
              (conj (str "-X" (:exec-aliases cli-opts)))
              tool?
              (conj "--tool-mode")
              tool-name
              (conj "--tool-name" tool-name)
              tool-aliases
              (conj (str "-T" tool-aliases))
              (:force-cp cli-opts)
              (conj "--skip-cp")
              (:threads cli-opts)
              (conj "--threads" (:threads cli-opts))
              (:trace cli-opts)
              (conj "--trace")
              tree?
              (conj "--tree")))
          classpath-not-needed? (boolean (some #(% cli-opts) [:describe :help :version]))]
      ;;  If stale, run make-classpath to refresh cached classpath
      (when (and stale (not classpath-not-needed?))
        (when (:verbose cli-opts)
          (warn "Refreshing classpath"))
        (let [{:keys [out]} (*aux-process-fn* {:cmd (into clj-main-cmd
                                                          (concat
                                                           ["-m" "clojure.tools.deps.script.make-classpath2"
                                                            "--config-user" config-user
                                                            "--config-project" (relativize config-project)
                                                            "--basis-file" (relativize basis-file)
                                                            "--cp-file" (relativize cp-file)
                                                            "--jvm-file" (relativize jvm-file)
                                                            "--main-file" (relativize main-file)
                                                            "--manifest-file" (relativize manifest-file)]
                                                           tools-args))
                                               :out (when tree?
                                                      :string)})]
          (when tree?
            (print out) (flush))))
      (let [cp (cond (or classpath-not-needed?
                         (:prep cli-opts)) nil
                     (not (str/blank? (:force-cp cli-opts))) (:force-cp cli-opts)
                     :else (slurp cp-file))]
        (cond (:help cli-opts) (do (print-help)
                                   (*exit-fn* {:exit 0}))
              (:version cli-opts) (do (println "Clojure CLI version (deps.clj)" @version)
                                      (*exit-fn* {:exit 0}))
              (:prep cli-opts) (*exit-fn* {:exit 0})
              (:pom cli-opts)
              (*aux-process-fn* {:cmd (into clj-main-cmd
                                            ["-m" "clojure.tools.deps.script.generate-manifest2"
                                             "--config-user" config-user
                                             "--config-project" (relativize config-project)
                                             "--gen=pom" (str/join " " tools-args)])})
              (:print-classpath cli-opts)
              (println cp)
              (:describe cli-opts)
              (describe [[:deps-clj-version deps-clj-version]
                         [:version @version]
                         [:config-files (filterv #(.exists (io/file %)) config-paths)]
                         [:config-user config-user]
                         [:config-project (relativize config-project)]
                         (when install-dir [:install-dir install-dir])
                         [:config-dir config-dir]
                         [:cache-dir cache-dir]
                         [:force (boolean (:force cli-opts))]
                         [:repro (boolean (:repro cli-opts))]
                         [:main-aliases (str (:main-aliases cli-opts))]
                         [:repl-aliases (str/join (:repl-aliases cli-opts))]])
              tree? (*exit-fn* {:exit 0})
              (:trace cli-opts)
              (warn "Wrote trace.edn")
              (:command cli-opts)
              (let [command (str/replace (:command cli-opts) "{{classpath}}" (str cp))
                    main-cache-opts (when (.exists (io/file main-file))
                                      (-> main-file slurp str/split-lines))
                    main-cache-opts (str/join " " main-cache-opts)
                    command (str/replace command "{{main-opts}}" (str main-cache-opts))
                    command (str/split command #"\s+")
                    command (into command (:args cli-opts))]
                (*clojure-process-fn* {:cmd command}))
              :else
              (let [jvm-cache-opts (when (.exists (io/file jvm-file))
                                     (-> jvm-file slurp str/split-lines))
                    main-cache-opts (when (.exists (io/file main-file))
                                      (-> main-file slurp str/split-lines))
                    main-opts (if (or exec? tool?)
                                ["-m" "clojure.run.exec"]
                                main-cache-opts)
                    cp (if (or exec? tool?)
                         (str cp path-separator exec-cp)
                         cp)
                    main-args (concat java-cmd
                                      java-opts
                                      proxy-settings
                                      jvm-cache-opts
                                      (:jvm-opts cli-opts)
                                      [(str "-Dclojure.basis=" (relativize basis-file))
                                       "-classpath" (auto-file-arg cp)
                                       "clojure.main"]
                                      main-opts)
                    main-args (filterv some? main-args)
                    main-args (into main-args (:args cli-opts))]
                (when (and (= :repl mode)
                           (pos? (count (:args cli-opts))))
                  (warn "WARNING: Implicit use of clojure.main with options is deprecated, use -M"))
                (*clojure-process-fn* {:cmd main-args})))))))
