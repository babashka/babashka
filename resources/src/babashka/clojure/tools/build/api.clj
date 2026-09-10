(ns clojure.tools.build.api
  (:require
    [clojure.java.io :as jio]
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [clojure.tools.build.util.file :as file]
    [clojure.tools.build.api.specs :as specs])
  (:import
    [java.io File]))

(set! *warn-on-reflection* true)

;; Project root dir, defaults to current directory

(def ^:dynamic *project-root*
  "Project root path, defaults to current directory.
  Use `resolve-path` to resolve relative paths in terms of the *project-root*.
  Use `set-project-root!` to override the default for all tasks."
  ".")

(defn set-project-root!
  "Set *project-root* dir (default is \".\")"
  [root]
  (alter-var-root #'*project-root* (constantly root)))

(defmacro with-project-root
  "Execute forms in a bound project path (string) other than the default (\".\")"
  [path & forms]
  `(binding [*project-root* ~path] ~@forms))

(defn resolve-path
  "If path is absolute or root-path is nil then return path,
  otherwise resolve relative to *project-root*."
  ^File [path]
  (let [path-file (jio/file path)]
    (if (.isAbsolute path-file)
      ;; absolute, ignore root
      path-file
      ;; relative to *project-root*
      (jio/file *project-root* path-file))))

(defn- assert-required
  "Check that each key in required coll is a key in params and throw if
  required are missing in params, otherwise return nil."
  [task params required]
  (let [missing (set/difference (set required) (set (keys params)))]
    (when (seq missing)
      (throw (ex-info (format "Missing required params for %s: %s" task (vec (sort missing))) (or params {}))))))

(defn- assert-specs
  "Check that key in params satisfies the spec. Throw if it exists and
  does not conform to the spec, otherwise return nil."
  [task params & key-specs]
  (doseq [[key spec] (partition-all 2 key-specs)]
    (let [val (get params key)]
      (when (and val (not (s/valid? spec val)))
        (throw (ex-info (format "Invalid param %s in call to %s: got %s, expected %s" key task (pr-str val) (s/form spec)) {}))))))

;; File tasks

(defn delete
  "Delete file or directory recursively, if it exists. Returns nil.

  Options:
    :path - required, path to file or directory"
  [{:keys [path] :as params}]
  (assert-required "delete" params [:path])
  (assert-specs "delete" params :path ::specs/path)
  (let [root-file (resolve-path path)]
    ;(println "root-file" root-file)
    (when (.exists root-file)
      (file/delete root-file))))

(defn copy-file
  "Copy one file from source to target, creating target dirs if needed.
  Returns nil.

  Options:
    :src - required, source path
    :target - required, target path"
  [{:keys [src target] :as params}]
  (assert-required "copy-file" params [:src :target])
  (assert-specs "copy-file" params
    :src ::specs/path
    :target ::specs/path)
  (file/copy-file (resolve-path src) (resolve-path target)))

(defn write-file
  "Writes a file at path, will create parent dirs if needed. Returns nil.
  File contents may be specified either with :content (for data, that
  will be pr-str'ed) or with :string for the string to write. If
  neither is specified, an empty file is created (like touch).

  Options:
    :path - required, file path
    :content - val to write, will pr-str
    :string - string to write
    :opts - coll of writer opts like :append and :encoding (per clojure.java.io)"
  [{:keys [path content string opts] :as params}]
  (assert-required "write-file" params [:path])
  (assert-specs "write-file" params :path ::specs/path)
  (let [f (resolve-path path)]
    (cond
      content (apply file/ensure-file f (pr-str content) opts)
      string (apply file/ensure-file f string opts)
      :else (file/ensure-file f))))

(defn copy-dir
  "Copy the contents of the src-dirs to the target-dir, optionally do text replacement.
  Returns nil.

  Globs are wildcard patterns for specifying sets of files in a directory
  tree, as specified in the glob syntax of java.nio.file.FileSystem:
  https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/nio/file/FileSystem.html#getPathMatcher(java.lang.String)

  Options:
    :target-dir - required, dir to write files, will be created if it doesn't exist
    :src-dirs   - required, coll of dirs to copy from
    :include    - glob of files to include, default = \"**\"
    :ignores    - collection of ignore regex patterns (applied only to file names),
                  see clojure.tools.build.tasks.copy/default-ignores for defaults
    :replace    - map of source to replacement string in files
    :non-replaced-exts - coll of extensions to skip when replacing (still copied)
                  default = [\"jpg\" \"jpeg\" \"png\" \"gif\" \"bmp\"]"
  [params]
  (assert-required "copy" params [:target-dir :src-dirs])
  (assert-specs "copy" params
    :target-dir ::specs/path
    :src-dirs ::specs/paths)
  ((requiring-resolve 'clojure.tools.build.tasks.copy/copy) params))

;; Basis tasks

(defn create-basis
  "Create a basis from a set of deps sources and a set of aliases. By default, use
  root, user, and project deps and no aliases (essentially the same classpath you
  get by default from the Clojure CLI).

  Each dep source value can be :standard, a string path, a deps edn map, or nil.
  Sources are merged in the order - :root, :user, :project, :extra.

  Options (note, paths resolved via *project-root*):
    :dir     - directory root path, defaults to current directory
    :root    - dep source, default = :standard
    :user    - dep source, default = nil (for reproducibility, not included)
    :project - dep source, default = :standard (\"./deps.edn\")
    :extra   - dep source, default = nil
    :aliases - coll of aliases of argmaps to apply to subprocesses

  Returns a runtime basis, which is the initial merged deps edn map plus these keys:
   :resolve-args - the resolve args passed in, if any
   :classpath-args - the classpath args passed in, if any
   :libs - lib map, per resolve-deps
   :classpath - classpath map per make-classpath-map
   :classpath-roots - vector of paths in classpath order"
  ([]
   ((requiring-resolve 'clojure.tools.build.tasks.create-basis/create-basis)))
  ([params]
   ((requiring-resolve 'clojure.tools.build.tasks.create-basis/create-basis) params)))

;; Process tasks

(defn java-command
  "Create Java command line args. The classpath will be the combination of
  :cp followed by the classpath from the basis, both are optional.

  Note that 'java-command' will NOT resolve any relative paths from basis
  or cp in terms of *project-root*, you will get a classpath with the same
  relative paths. 'process' (if run with this output), will run in the
  context of the *project-root* directory.

  Options:
    :java-cmd - Java command, default = $JAVA_CMD or 'java' on $PATH, or $JAVA_HOME/bin/java
    :cp - coll of string classpath entries, used first (if provided)
    :basis - runtime basis used for classpath and jvm opts from aliases, used last (if provided)
    :java-opts - coll of string jvm opts
    :main - required, main class symbol
    :main-args - coll of main class args
    :use-cp-file - one of:
                     :auto (default) - use only if os=windows && Java >= 9 && command length >= 8k
                     :always - always write classpath to temp file and include
                     :never - never write classpath to temp file (pass on command line)

  Returns map suitable for passing to 'process' with keys:
    :command-args - coll of command arg strings"
  [params]
  (assert-required "java-command" params [:main])
  ((requiring-resolve 'clojure.tools.build.tasks.process/java-command) params))

(defn process
  "Exec the command made from command-args, redirect out and err as directed,
  and return {:exit exit-code, :out captured-out, :err captured-err}.

  Options:
    :command-args - required, coll of string args
    :dir - directory to run the command from, default *project-root*
    :out - one of :inherit :capture :write :append :ignore
    :err - one of :inherit :capture :write :append :ignore
    :out-file - file path to write if :out is :write or :append
    :err-file - file path to write if :err is :write or :append
    :env - map of environment variables to set

  The :out and :err input flags take one of the following options:
    :inherit - inherit the stream and write the subprocess io to this process's stream (default)
    :capture - capture the stream to a string and return it
    :write - write to :out-file or :err-file
    :append - append to :out-file or :err-file
    :ignore - ignore the stream"
  [params]
  (assert-required "process" params [:command-args])
  (assert-specs "process" params
    :dir ::specs/path
    :out-file ::specs/path
    :err-file ::specs/path)
  ((requiring-resolve 'clojure.tools.build.tasks.process/process) params))

;; Git tasks

(defn git-process
  "Run git process in the specified dir using git-command with git-args (which should not
  start with \"git\"). git-args may either be a string (split on whitespace) or a vector
  of strings. By default, stdout is captured, trimmed, and returned.

  Options:
    :dir - dir to invoke this command from, default = current directory
    :git-command - git command to use, default = \"git\"
    :git-args - required, coll of git-arg strings forming a command line OR
                a string (do not use if args may have embedded spaces)
    :capture - :out (default) or :err, else nothing

  Examples:
    (api/git-process {:git-args \"rev-list HEAD --count\"})
    (api/git-process {:git-args \"branch --show-current\"})
    (api/git-process {:git-args \"rev-parse --short HEAD\"})
    (api/git-process {:git-args \"push\", :capture nil})"
  [params]
  (assert-required "git-command" params [:git-args])
  (assert-specs "git-process" params
    :dir ::specs/path
    :git-command (s/nilable string?)
    :git-args (s/or :args (s/coll-of string?) :line string?)
    :capture (s/nilable keyword?))
  (let [{:keys [dir git-command git-args capture] :or {git-command "git", capture :out}} params
        git-args (vec
                   (concat [git-command]
                     (if (string? git-args)
                       (str/split git-args #"\s")
                       git-args)))
        proc-params (cond-> {:command-args git-args}
                      capture (assoc capture :capture)
                      dir (assoc :dir (.getPath (resolve-path dir))))
        output (process proc-params)]
    (when capture
      (some-> output capture str/trim))))

(defn git-count-revs
  "Shells out to git and returns count of commits on this branch:
    git rev-list HEAD --count

  Options:
    :dir - dir to invoke this command from, default = current directory
    :git-command - git command to use, default = \"git\"
    :path - path to count commits for relative to dir"
  [{:keys [dir git-command path] :or {git-command "git"} :as params}]
  (assert-specs "git-count-revs" params
    :dir ::specs/path
    :git-command (s/nilable string?)
    :path ::specs/path)
  (git-process
    (cond-> {:git-args (cond-> ["rev-list" "HEAD" "--count"]
                         path (conj "--" path))}
      dir (assoc :dir dir)
      git-command (assoc :git-command git-command))))

;; Compile tasks

(defn compile-clj
  "Compile Clojure source to classes in :class-dir.

  Clojure source files are found in :basis :paths by default, or override with :src-dirs.

  Namespaces and order of compilation are one of:
    * :ns-compile - compile these namespaces, in this order
    * :sort - find all namespaces in source files and use either :topo (default)
              or :bfs to order them for compilation

  Options:
    :basis - required, basis to use when compiling
    :class-dir - required, dir to write classes, will be created if needed
    :src-dirs - coll of Clojure source dirs, used to find all Clojure nses to compile
    :ns-compile - coll of specific namespace symbols to compile
    :sort - :topo (default) or :bfs for breadth-first search
    :compile-opts - map of Clojure compiler options:
      {:disable-locals-clearing false
       :elide-meta [:doc :file :line ...]
       :direct-linking false}
    :bindings - map of Var to value to be set during compilation, for example:
      {#'clojure.core/*assert* false
       #'clojure.core/*warn-on-reflection* true}
    :filter-nses - coll of symbols representing a namespace prefix to include

  Additional options flow to the forked process doing the compile:
    :java-cmd - Java command, default = $JAVA_CMD or 'java' on $PATH, or $JAVA_HOME/bin/java
    :java-opts - coll of string jvm opts
    :use-cp-file - one of:
                     :auto (default) - use only if os=windows && Java >= 9 && command length >= 8k
                     :always - always write classpath to temp file and include
                     :never - never write classpath to temp file (pass on command line)
    :out - one of :inherit :capture :write :append :ignore
    :err - one of :inherit :capture :write :append :ignore
    :out-file - file path to write if :out is :write or :append
    :err-file - file path to write if :err is :write or :append

  Returns nil, or if needed a map with keys:
    :out captured-out
    :err captured-err"
  [params]
  (assert-required "compile-clj" params [:basis :class-dir])
  (assert-specs "compile-clj" params
    :class-dir ::specs/path
    :src-dirs ::specs/paths
    :compile-opts map?
    :bindings map?
    :basis ::specs/basis)
  ((requiring-resolve 'clojure.tools.build.tasks.compile-clj/compile-clj) params))

(defn javac
  "Compile Java source to classes. Returns nil.

  Options:
    :src-dirs - required, coll of Java source dirs
    :class-dir - required, dir to write classes, will be created if needed
    :basis - classpath basis to use when compiling
    :javac-opts - coll of string opts, like [\"-source\" \"8\" \"-target\" \"8\"]"
  [params]
  (assert-required "javac" params [:src-dirs :class-dir])
  (assert-specs "javac" params
    :src-dirs ::specs/paths
    :class-dir ::specs/path)
  ((requiring-resolve 'clojure.tools.build.tasks.javac/javac) params))

;; Jar/zip tasks

(defn pom-path
  "Calculate path to pom.xml in jar meta (same path used by write-pom).
  Relative path in jar is:
    META-INF/maven/<groupId>/<artifactId>/pom.xml

  If :class-dir provided, return path will start with resolved class-dir
  (which may be either absolute or relative), otherwise just relative
  path in jar.

  Options:
    :lib - required, used to form the relative path in jar to pom.xml
    :class-dir - optional, if provided will be resolved and form the root of the path"
  [params]
  (assert-required "pom-path" params [:lib])
  (assert-specs "pom-path" params
    :lib ::specs/lib
    :class-dir ::specs/path)
  (let [{:keys [class-dir lib]} params
        pom-dir ((requiring-resolve 'clojure.tools.build.tasks.write-pom/meta-maven-path) {:lib lib})
        pom-file (if class-dir
                   (jio/file (resolve-path class-dir) pom-dir "pom.xml")
                   (jio/file pom-dir "pom.xml"))]
    (.toString pom-file)))

(defn write-pom
  "Write pom.xml and pom.properties files to the class dir under
  META-INF/maven/group-id/artifact-id/ (where Maven typically writes
  these files), or to target (exactly one of :class-dir and :target must
  be provided).

  Optionally use :src-pom to provide a pom template (or a default will
  be generated from the provided attributes). The pom deps, dirs, and
  repos from the basis will replace those sections of the template. Note
  that the :src-pom template is not validated and should contain required
  elements such as modelVersion.

  If a repos map is provided it supersedes the repos in the basis.

  Returns nil.

  Options:
    :basis - required, used to pull deps, repos
    :src-pom - source pom.xml to synchronize from, default = \"./pom.xml\"
               may be :none to ignore any existing pom.xml file
    :class-dir - root dir for writing pom files, created if needed
    :target - file path to write pom if no :class-dir specified
    :lib - required, project lib symbol
    :version - required, project version
    :scm - map of scm properties to write in pom
           keys:  :connection, :developerConnection, :tag, :url
           See: https://maven.apache.org/pom.html#SCM for details
    :src-dirs - coll of src dirs
    :resource-dirs - coll of resource dirs
    :repos - map of repo name to repo config, replaces repos from deps.edn
    :pom-data - vector of hiccup-style extra pom top elements to include when
      :src-pom is :none or the source pom.xml does not exist:
       [[:licenses
         [:license
          [:name \"Eclipse Public License 1.0\"]
          [:url \"https://opensource.org/license/epl-1-0/\"]
          [:distribution \"repo\"]]]
        [:organization \"Super Corp\"]]
      The pom-data MUST NOT include:
        :modelVersion, :packaging, :groupId, :artifactId, :version, :name,
        :deps, :repositories, :build, or :scm"
  [params]
  (assert-required "write-pom" params [:basis :lib :version])
  (assert-specs "write-pom" params
    :src-pom (s/or :none #{:none} :path ::specs/path)
    :class-dir ::specs/path
    :target ::specs/path
    :lib ::specs/lib
    :version string?
    :scm map?
    :src-dirs ::specs/paths
    :resource-dirs ::specs/paths
    :pom-data vector?)
  ((requiring-resolve 'clojure.tools.build.tasks.write-pom/write-pom) params))

(defn jar
  "Create jar file containing contents of class-dir. Use main in the manifest
  if provided. Returns nil.

  Options:
    :class-dir - required, dir to include in jar
    :jar-file - required, jar to write
    :main - main class symbol
    :manifest - map of manifest attributes, merged last over defaults+:main"
  [params]
  (assert-required "jar" params [:class-dir :jar-file])
  (assert-specs "jar" params
    :class-dir ::specs/path
    :jar-file ::specs/path)
  ((requiring-resolve 'clojure.tools.build.tasks.jar/jar) params))

(defn uber
  "Create uberjar file. An uberjar is a self-contained jar file containing
  both the project contents AND the contents of all dependencies.

  The project contents are represented by the class-dir. Use other tasks to
  put Clojure source, class files, a pom file, or other resources in the
  class-dir. In particular, see the copy-dir, write-pom, compile-clj, and
  javac tasks.

  The dependencies are pulled from the basis. All transitive deps will be
  included. Dependency jars are expanded for inclusion in the uberjar.
  Use :exclude to exclude specific paths from the expanded deps. Use
  conflict-handlers to handle conflicts that may occur if two dependency
  jar files include a file at the same path. See below for more detail.

  If a main class or manifest are provided, those are put in the uberjar
  META-INF/MANIFEST.MF file. Providing a main allows the jar to be
  invoked with java -jar.

  Returns nil.

  Options:
    :uber-file - required, uber jar file to create
    :class-dir - required, local class dir to include
    :basis - used to pull dep jars
    :main - main class symbol
    :manifest - map of manifest attributes, merged last over defaults + :main
    :exclude - coll of string patterns (regex) to exclude from deps
    :conflict-handlers - map of string pattern (regex) to built-in handlers,
                         symbols to eval, or function instances

  When combining jar files into an uber jar, multiple jars may contain a file
  at the same path. The conflict handlers are a map of string regex pattern
  to:
    a keyword (to use a built-in handler) or
    a symbol (to resolve and invoke) or
    a function instance
  The special key `:default` specifies the default behavior if not matched.

  Conflict handler signature (fn [params]) => effect-map:
    params:
      :path     - String, path in uber jar, matched by regex
      :in       - InputStream to incoming file (see stream->string if needed)
      :existing - File, existing File at path
      :lib      - symbol, lib source for incoming conflict
      :state    - map, available for retaining state during uberjar process

  Handler should return effect-map with optional keys:
    :state      - updated state map
    :write      - map of string path to map of :string (string) or
                  :stream (InputStream) to write and optional :append
                  flag. Omit if no files to write.

  Available built-in conflict handlers:
    :ignore - don't do anything (default)
    :overwrite - overwrite (replaces prior file)
    :append - append the file with a blank line separator
    :append-dedupe - append the file but dedupe appended sections
    :data-readers - merge data_readers.clj
    :warn - print a warning
    :error - throw an error

  Default conflict handlers map:
    {\"^data_readers.clj[c]?$\" :data-readers
     \"^META-INF/services/.*\" :append
     \"(?i)^(META-INF/)?(COPYRIGHT|NOTICE|LICENSE)(\\\\.(txt|md))?$\" :append-dedupe
     :default :ignore}"
  [params]
  (assert-required "uber" params [:class-dir :uber-file])
  (assert-specs "uber" params
    :class-dir ::specs/path
    :uber-file ::specs/path)
  ((requiring-resolve 'clojure.tools.build.tasks.uber/uber) params))

(defn zip
  "Create zip file containing contents of src dirs. Returns nil.

  Options:
    :src-dirs - required, coll of source directories to include in zip
    :zip-file - required, zip file to create"
  [params]
  (assert-required "zip" params [:src-dirs :zip-file])
  (assert-specs "zip" params
    :src-dirs ::specs/paths
    :zip-file ::specs/path)
  ((requiring-resolve 'clojure.tools.build.tasks.zip/zip) params))

(defn unzip
  "Unzip zip file to target-dir. Returns nil.

  Options:
    :zip-file - required, zip file to unzip
    :target-dir - required, directory to unzip in"
  [params]
  (assert-required "unzip" params [:zip-file :target-dir])
  (assert-specs "unzip" params
    :zip-file ::specs/path
    :target-dir ::specs/path)
  ((requiring-resolve 'clojure.tools.build.tasks.zip/unzip) params))

;; Maven tasks

(defn install
  "Install pom and jar to local Maven repo.
  Returns nil.

  Options:
    :basis - required, used for :mvn/local-repo
    :lib - required, lib symbol
    :classifier - classifier string, if needed
    :version - required, string version
    :jar-file - required, path to jar file
    :class-dir - required, used to find the pom file"
  [params]
  (assert-required "install" params [:basis :lib :version :jar-file :class-dir])
  (assert-specs "install" params
    :lib ::specs/lib
    :jar-file ::specs/path
    :class-dir ::specs/path)
  ((requiring-resolve 'clojure.tools.build.tasks.install/install) params))
