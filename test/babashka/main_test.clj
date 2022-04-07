(ns babashka.main-test
  {:clj-kondo/config '{:linters {:unresolved-symbol {:exclude [working?]}}}}
  (:require
   [babashka.main :as main]
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is testing]]
   [flatland.ordered.map :refer [ordered-map]]
   [sci.core :as sci]))

(defn bb [input & args]
  (test-utils/normalize
   (edn/read-string
    {:readers *data-readers*
     :eof nil}
    (apply test-utils/bb (when (some? input) (str input)) (map str args)))))

(defn parse-opts [args]
  (let [[args global-opts] (main/parse-global-opts args)]
    (main/parse-opts args global-opts)))

(deftest parse-opts-test
  (is (= "1667"
         (:nrepl (parse-opts ["--nrepl-server"]))))
  (is (= "1666"
         (:socket-repl (parse-opts ["--socket-repl"]))))
  (is (= {:nrepl "1667", :classpath "src"}
         (parse-opts ["--nrepl-server" "-cp" "src"])))
  (is (= {:nrepl "1667", :classpath "src"}
         (parse-opts ["-cp" "src" "nrepl-server"])))
  (is (= {:socket-repl "1666", :expressions ["123"]}
         (parse-opts ["--socket-repl" "-e" "123"])))
  (is (= {:socket-repl "1666", :expressions ["123"]}
         (parse-opts ["--socket-repl" "1666" "-e" "123"])))
  (is (= {:nrepl "1666", :expressions ["123"]}
         (parse-opts ["--nrepl-server" "1666" "-e" "123"])))
  (is (= {:classpath "src"
          :uberjar "foo.jar"}
         (parse-opts ["--classpath" "src" "uberjar" "foo.jar"])))
  (is (= {:classpath "src"
          :uberjar "foo.jar"
          :debug true}
         (parse-opts ["--debug" "--classpath" "src" "uberjar" "foo.jar"])))
  (is (= "src" (:classpath (parse-opts ["--classpath" "src"]))))
  (is (:debug (parse-opts ["--debug"])))
  (is (= 123 (bb nil "(println 123)")))
  (is (= 123 (bb nil "-e" "(println 123)")))
  (is (= 123 (bb nil "--eval" "(println 123)")))
  (testing "distinguish automatically between expression or file name"
    (is (= {:result 8080} (bb nil "test/babashka/scripts/tools.cli.bb")))
    (is (thrown-with-msg? Exception #"does not exist" (bb nil "foo.clj")))
    (is (thrown-with-msg? Exception #"does not exist" (bb nil "-help"))))
  (is (= "1 2 3" (bb nil "-e" "(require '[clojure.string :as str1])" "-e" "(str1/join \" \" [1 2 3])")))
  (is (= '("-e" "1") (bb nil "-e" "*command-line-args*" "--" "-e" "1")))
  (let [v (bb nil "--describe")]
    (is (:babashka/version v))
    (is (:feature/xml v)))
  (is (= {:force? true} (parse-opts ["--force"])))
  (is (= {:main "foo", :command-line-args '("-h")} (parse-opts ["-m" "foo" "-h"])))
  (is (= {:main "foo", :command-line-args '("-h")} (parse-opts ["-m" "foo" "--" "-h"]))))

(deftest version-test
  (is (= [1 0 0] (main/parse-version "1.0.0-SNAPSHOT")))
  (is (main/satisfies-min-version? "0.1.0"))
  (is (main/satisfies-min-version? "0.1.0-SNAPSHOT"))
  (is (not (main/satisfies-min-version? "300.0.0")))
  (is (not (main/satisfies-min-version? "300.0.0-SNAPSHOT"))))

(deftest print-error-test
  (is (thrown-with-msg? Exception #"java.lang.NullPointerException"
                        (bb nil "(subs nil 0 0)"))))

(deftest input-test
  (testing "-io behaves as identity"
    (is (= "foo\nbar\n" (test-utils/bb "foo\nbar\n" "-io" "*input*"))))
  (testing "if and when"
    (is (= 1 (bb 0 '(if (zero? *input*) 1 2))))
    (is (= 2 (bb 1 '(if (zero? *input*) 1 2))))
    (is (= 1 (bb 0 '(when (zero? *input*) 1))))
    (is (nil? (bb 1 '(when (zero? *input*) 1)))))
  (testing "and and or"
    (is (= false (bb 0 '(and false true *input*))))
    (is (= 0 (bb 0 '(and true true *input*))))
    (is (= 1 (bb 1 '(or false false *input*))))
    (is (= false (bb false '(or false false *input*))))
    (is (= 3 (bb false '(or false false *input* 3)))))
  (testing "fn"
    (is (= 2 (bb 1 "(#(+ 1 %) *input*)")))
    (is (= [1 2 3] (bb 1 "(map #(+ 1 %) [0 1 2])")))
    (is (= 1 (bb 1 "(#(when (odd? *input*) *input*))"))))
  (testing "map"
    (is (= [1 2 3] (bb 1 '(map inc [0 1 2])))))
  (testing "keep"
    (is (= [false true false] (bb 1 '(keep odd? [0 1 2])))))
  (testing "->"
    (is (= 4 (bb 1 '(-> *input* inc inc (inc))))))
  (testing "->>"
    (is (= 10 (edn/read-string (test-utils/bb "foo\n\baar\baaaaz" "-i" "(->> *input* (map count) (apply max))")))))
  (testing "literals"
    (is (= {:a 4
            :b {:a 2}
            :c [1 1]
            :d #{1 2}}
           (bb 1 '{:a (+ 1 2 *input*)
                   :b {:a (inc *input*)}
                   :c [*input* *input*]
                   :d #{*input* (inc *input*)}}))))
  (testing "shuffle the contents of a file"
    (let [in "foo\n Clojure is nice. \nbar\n If you're nice to clojure. "
          in-lines (set (str/split in #"\n"))
          out (test-utils/bb in
                             "-io"
                             (str '(shuffle *input*)))
          out-lines (set (str/split out #"\n"))]
      (is (= in-lines out-lines))))
  (testing "find occurrences in file by line number"
    (is (= '(1 3)
           (->
            (bb "foo\n Clojure is nice. \nbar\n If you're nice to clojure. "
                "-i"
                "(map-indexed #(-> [%1 %2]) *input*)")
            (bb "(keep #(when (re-find #\"(?i)clojure\" (second %)) (first %)) *input*)")))))
  (testing "ordered/map data reader works"
    (is (= "localhost" (bb "#ordered/map ([:test \"localhost\"])"
                           "(:test *input*)"))))
  (testing "bb doesn't wait for input if *input* isn't used"
    (is (= "2\n" (test-utils/normalize (sci/with-out-str (main/main "(inc 1)")))))))

(deftest println-test
  (is (= "hello\n" (test-utils/bb nil "(println \"hello\")"))))

(deftest System-test
  (let [res (bb nil "-f" "test/babashka/scripts/System.bb")]
    (is (= "bar" (second res)))
    (doseq [s res]
      (is (not-empty s)))))

(deftest malformed-command-line-args-test
  (is (thrown-with-msg? Exception #"File does not exist: non-existing"
                        (bb nil "-f" "non-existing"))))

(deftest ssl-test
  (let [resp (bb nil "(slurp \"https://www.google.com\")")]
    (is (re-find #"doctype html" resp))))

(deftest stream-test
  (is (= "2\n3\n4\n" (test-utils/bb "1 2 3" "--stream" "(inc *input*)")))
  (is (= "2\n3\n4\n" (test-utils/bb "{:x 2} {:x 3} {:x 4}" "--stream" "(:x *input*)")))
  (let [x "foo\n\bar\n"]
    (is (= x (test-utils/bb x "--stream" "-io" "*input*"))))
  (let [x "f\n\b\n"]
    (is (= x (test-utils/bb x "--stream" "-io" "(subs *input* 0 1)")))))

(deftest load-file-test
  (let [tmp (java.io.File/createTempFile "script" ".clj")]
    (.deleteOnExit tmp)
    (spit tmp "(ns foo) (defn foo [x y] (+ x y)) (defn bar [x y] (* x y))")
    (is (= "120\n" (test-utils/bb nil (format "(load-file \"%s\") (foo/bar (foo/foo 10 30) 3)"
                                              (test-utils/escape-file-paths (.getPath tmp))))))
    (testing "namespace is restored after load file"
      (is (= 'start-ns
             (bb nil (format "(ns start-ns) (load-file \"%s\") (ns-name *ns*)"
                             (test-utils/escape-file-paths (.getPath tmp)))))))))

(deftest repl-source-test
  (let [tmp (java.io.File/createTempFile "lib" ".clj")
        name (str/replace (.getName tmp) ".clj" "")
        dir (.getParent tmp)]
    (.deleteOnExit tmp)
    (testing "print source from loaded file"
      (spit tmp (format "
(ns %s)

(defn foo [x y]
  (+ x y))" name))
      (is (= "(defn foo [x y]\n  (+ x y))\n"
             (bb nil (format "
(load-file \"%s\")
(require '[clojure.repl :refer [source]])
(with-out-str (source %s/foo))"
                             (test-utils/escape-file-paths (.getPath tmp))
                             name)))))
    (testing "print source from file on classpath"
      (is (= "(defn foo [x y]\n  (+ x y))\n"
             (bb nil
                 "-cp" dir
                 "-e" (format "(require '[clojure.repl :refer [source]] '[%s])" name)
                 "-e" (format "(with-out-str (source %s/foo))" name)))))))

(deftest eval-test
  (is (= "120\n" (test-utils/bb nil "(eval '(do (defn foo [x y] (+ x y))
                                                (defn bar [x y] (* x y))
                                                (bar (foo 10 30) 3)))"))))

(deftest init-test
  (testing "init with a file"
    (is (= "foo" (bb nil "--init" "test-resources/babashka/init_test.clj" 
                   "-f" "test-resources/babashka/init_caller.clj"))))
  (testing "init with eval(s)"
    (is (= "foo" (bb nil "--init" "test-resources/babashka/init_test.clj"
                   "-e" "(init-test/do-a-thing)"))))
  (testing "init with main from init'ed ns"
    (is (= "Hello from init!" (bb nil "--init" "test-resources/babashka/init_test.clj"
                                "-m" "init-test"))))
  (testing "init with main from another namespace"
    (test-utils/with-config '{:paths ["test-resources/babashka/src_for_classpath_test"]}
      (is (= "foo" (bb nil "--init" "test-resources/babashka/init_test.clj"
                     "-m" "call-init-main")))))
  (testing "init with a qualified function passed to --main"
    (test-utils/with-config '{:paths ["test-resources/babashka/src_for_classpath_test"]}
      (is (= "foobar" (bb nil "--init" "test-resources/babashka/init_test.clj"
                        "-m" "call-init-main/foobar")))))
  (testing "init with a subcommand after it"
    (let [actual-output (test-utils/bb "(println (init-test/do-a-thing))"
                          "--init" "test-resources/babashka/init_test.clj" "repl")]
      (is (str/includes? actual-output "foo\n")))
    (test-utils/with-config '{:tasks {thing (println (init-test/do-a-thing))}} ; make a task available 
      (let [actual-output (test-utils/bb nil "--init" "test-resources/babashka/init_test.clj" "tasks")]
        (is (every? #(str/includes? actual-output %) ["following tasks are available" "thing"])))))
  (testing "init with a task name after it"
    (test-utils/with-config '{:tasks {thing (println (init-test/do-a-thing))}} ; make a task available 
      (is (= "foo\n" (test-utils/bb nil "--init" "test-resources/babashka/init_test.clj" "thing"))))))

(deftest preloads-test
  ;; THIS TEST REQUIRES:
  ;; export BABASHKA_PRELOADS='(defn __bb__foo [] "foo") (defn __bb__bar [] "bar")'
  (when (System/getenv "BABASHKA_PRELOADS_TEST")
    (is (= "foobar" (bb nil "(str (__bb__foo) (__bb__bar))")))))

(deftest io-test
  (is (true? (bb nil "(.exists (io/file \"README.md\"))")))
  (is (true? (bb nil "(.canWrite (io/file \"README.md\"))"))))

; skipped because the windows shell doesn't seem to deal well with infinite things
(deftest ^:skip-windows pipe-test
  (when (and test-utils/native?
             (not main/windows?))
    (let [out (:out (sh "bash" "-c" "./bb -o '(range)' |
                         ./bb --stream '(* *input* *input*)' |
                         head -n10"))
          out (str/split-lines out)
          out (map edn/read-string out)]
      (is (= (take 10 (map #(* % %) (range))) out)))
    (let [out (:out (sh "bash" "-c" "./bb -O '(repeat \"dude\")' |
                         ./bb --stream '(str *input* \"rino\")' |
                         ./bb -I '(take 3 *input*)'"))
          out (edn/read-string out)]
      (is (= '("duderino" "duderino" "duderino") out)))))

(deftest ^:windows-only win-pipe-test
  (when (and test-utils/native? main/windows?)
    (let [out (:out (sh "cmd" "/c" ".\\bb -O \"(repeat 50 \\\"dude\\\")\" |"
                         ".\\bb --stream \"(str *input* \\\"rino\\\")\" |"
                         ".\\bb -I \"(take 3 *input*)\""))
          out (edn/read-string out)]
      (is (= '("duderino" "duderino" "duderino") out)))))

(deftest ^:skip-windows lazy-text-in-test
  (when test-utils/native?
    (let [out (:out (sh "bash" "-c" "yes | ./bb -i '(take 2 *input*)'"))
          out (edn/read-string out)]
      (is (= '("y" "y") out)))))

(deftest future-test
  (is (= 6 (bb nil "@(future (+ 1 2 3))"))))

(deftest promise-test
  (is (= :timeout (bb nil "(deref (promise) 1 :timeout)")))
  (is (= :ok (bb nil "(let [x (promise)]
                        (deliver x :ok)
                        @x)"))))

(deftest process-builder-test
  (let [cmd-line (if main/windows?
                   "[\"cmd\" \"/c\" \"dir\"]"
                   "[\"ls\"]")]
    (is (str/includes? (bb nil (str "
(def pb (ProcessBuilder. " cmd-line "))
(def env (.environment pb))
(.put env \"FOO\" \"BAR\") ;; test for issue 460
(def ls (-> pb (.start)))
(def input (.getOutputStream ls))
(.write (io/writer input) \"hello\") ;; dummy test just to see if this works
(def output (.getInputStream ls))
(assert (int? (.waitFor ls)))
(slurp output)"))
                       "LICENSE")))
  (testing "bb is able to kill subprocesses created by ProcessBuilder"
    (when test-utils/native?
      (let [process-count (if main/windows? 6 3)
            output (test-utils/bb nil (io/file "test" "babashka" "scripts" "kill_child_processes.bb"))
            parsed (edn/read-string (format "[%s]" output))]
        (is (every? number? parsed))
        (is (= process-count (count parsed)))))))

(deftest create-temp-file-test
  (is (= true
        (bb nil "(let [tfile (File/createTempFile \"ctf\" \"tmp\")]
                             (.deleteOnExit tfile) ; for cleanup
                             (.exists tfile))"))))

(deftest wait-for-port-test
  (let [server (test-utils/start-server! 1777)]
    (is (= 1777 (:port (bb nil "(wait/wait-for-port \"127.0.0.1\" 1777)"))))
    (test-utils/stop-server! server)
    (is (= :timed-out (bb nil "(wait/wait-for-port \"127.0.0.1\" 1777 {:default :timed-out :timeout 50})"))))
  (let [edn (bb nil (io/file "test" "babashka" "scripts" "socket_server.bb"))]
    (is (= "127.0.0.1" (:host edn)))
    (is (=  1777 (:port edn)))
    (is (number? (:took edn)))))

(deftest ^:skip-windows wait-for-path-test
  (let [temp-dir-path (System/getProperty "java.io.tmpdir")]
    (is (not= :timed-out
              (bb nil (format "(let [tdir (io/file \"%s\")
                                 tfile
                                 (File/createTempFile \"wfp\" \"tmp\" tdir)
                                 tpath (.getPath tfile)]
                             (.delete tfile) ; delete now, but re-create it in a future
                             (future (Thread/sleep 50) (shell/sh \"touch\" tpath))
                             (wait/wait-for-path tpath
                               {:default :timed-out :timeout 100})
                             (.delete tfile))"
                              temp-dir-path))))
    (is (= :timed-out
           (bb nil (format "(let [tdir (io/file \"%s\")
                                 tfile
                                 (File/createTempFile \"wfp-to\" \"tmp\" tdir)
                                 tpath (.getPath tfile)]
                             (.delete tfile) ; for timing out test and cleanup
                             (wait/wait-for-path tpath
                               {:default :timed-out :timeout 100}))"
                           temp-dir-path))))))

(deftest tools-cli-test
  (is (= {:result 8080} (bb nil "test/babashka/scripts/tools.cli.bb"))))

(deftest try-catch-test
  (is (zero? (bb nil "(try (/ 1 0) (catch ArithmeticException _ 0))")))
  (is (= :got-it (bb nil "
(defn foo []
  (throw (java.util.MissingResourceException. \"o noe!\" \"\" \"\")))

(defn bar
  []
  (try (foo)
       (catch java.util.MissingResourceException _
         :got-it)))
(bar)
"))))

(deftest reader-conditionals-test
  (is (= :hello (bb nil "#?(:bb :hello :default :bye)")))
  (is (= :hello (bb nil "#? (:bb :hello :default :bye)")))
  (is (= :hello (bb nil "#?(:clj :hello :bb :bye)")))
  (is (= [1 2] (bb nil "[1 2 #?@(:bb [] :clj [1])]"))))

(deftest csv-test
  (is (= '(["Adult" "87727"] ["Elderly" "43914"] ["Child" "33411"] ["Adolescent" "29849"]
           ["Infant" "15238"] ["Newborn" "10050"] ["In Utero" "1198"])
         (bb nil (.getPath (io/file "test" "babashka" "scripts" "csv.bb"))))))

(deftest assert-test ;; assert was first implemented in bb but moved to sci later
  (is (thrown-with-msg? Exception #"should-be-true"
                        (bb nil "(def should-be-true false) (assert should-be-true)"))))

(deftest Pattern-test
  (is (= ["1" "2" "3"]
         (bb nil "(vec (.split (java.util.regex.Pattern/compile \"f\") \"1f2f3\"))")))
  (is (true? (bb nil "(some? java.util.regex.Pattern/CANON_EQ)"))))

(deftest writer-test
  (let [tmp-file (java.io.File/createTempFile "bbb" "bbb")
        path (test-utils/escape-file-paths (.getPath tmp-file))]
    (bb nil (format "(with-open [w (io/writer \"%s\")]
                       (.write w \"foobar\n\")
                       (.append w \"barfoo\n\")
                       nil)"
                    path))
    (is (= "foobar\nbarfoo\n" (slurp path)))))

(deftest binding-test
  (is (= (if main/windows? 7 6)
        (bb nil "(def w (java.io.StringWriter.))
                 (binding [clojure.core/*out* w]
                   (println \"hello\"))
                 (count (str w))"))))

(deftest with-out-str-test
  (is (= (if main/windows? 7 6)
        (bb nil "(count (with-out-str (println \"hello\")))"))))

(deftest with-in-str-test
  (is (= 5 (bb nil "(count (with-in-str \"hello\" (read-line)))"))))

(deftest java-nio-test
  (let [f (java.io.File/createTempFile "foo" "bar")
        temp-path (test-utils/escape-file-paths (.getPath f))
        p (.toPath (io/file f))
        p' (.resolveSibling p "f2")
        f2 (.toFile p')]
    (bb nil (format
             "(let [f (io/file \"%s\")
                    p (.toPath (io/file f))
                    p' (.resolveSibling p \"f2\")]
                (.delete (.toFile p'))
                (dotimes [_ 2]
                  (try
                    (java.nio.file.Files/copy p p' (into-array java.nio.file.CopyOption []))
                   (catch java.nio.file.FileAlreadyExistsException _
                     (java.nio.file.Files/copy p p' (into-array [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))))"
             temp-path))
    (is (.exists f2))
    (let [v (bb nil "-f" (.getPath (io/file "test-resources" "babashka" "glob.clj")))]
      (is (vector? v))
      (is (.exists (io/file (first v)))))
    (is (= :success (bb nil "(with-open [str (java.nio.file.Files/newDirectoryStream (.toPath (clojure.java.io/file \".\")))] :success)")))
    (is (string? (bb nil
                     '(do (import [java.nio.file Files LinkOption])
                          (import [java.nio.file.attribute BasicFileAttributes])
                          (def attrs (Files/readAttributes (.toPath (io/file ".")) BasicFileAttributes ^"[Ljava.nio.file.LinkOption;"
                                                           (into-array LinkOption [])))
                          (str (.lastModifiedTime attrs))))))))

(deftest future-print-test
  (testing "the root binding of sci/*out*"
    (is (= "hello"  (bb nil "@(future (prn \"hello\"))")))))

(deftest Math-test
  (is (== 8.0 (bb nil "(Math/pow 2 3)"))))

(deftest Base64-test
  (is (= "babashka"
         (bb nil "(String. (.decode (java.util.Base64/getDecoder) (.encode (java.util.Base64/getEncoder) (.getBytes \"babashka\"))))"))))

(deftest Thread-test
  (is (= "hello" (bb nil "(doto (java.lang.Thread. (fn [] (prn \"hello\"))) (.start) (.join)) nil"))))

(deftest dynvar-test
  (is (= 1 (bb nil "(binding [*command-line-args* 1] *command-line-args*)"))))

(deftest file-in-error-msg-test
  (is (thrown-with-msg? Exception #"error.bb"
                        (bb nil (.getPath (io/file "test" "babashka" "scripts" "error.bb"))))))

(deftest compatibility-test
  (is (true? (bb nil "(set! *warn-on-reflection* true)"))))

(deftest clojure-main-repl-test
  (let [expected-outcome (if main/windows?
                           "\"> foo!\\r\\nnil\\r\\n> \"\n"
                           "\"> foo!\\nnil\\n> \"\n")]
    (is (= expected-outcome (test-utils/bb nil "
(defn foo [] (println \"foo!\"))
(with-out-str
  (with-in-str \"(foo)\"
    (clojure.main/repl :init (fn []) :prompt (fn [] (print \"> \")))))")))))

(deftest command-line-args-test
  (is (true? (bb nil "(nil? *command-line-args*)")))
  (is (= ["1" "2" "3"] (bb nil "*command-line-args*" "1" "2" "3"))))

(deftest constructors-test
  (testing "the clojure.lang.Delay constructor works"
    (is (= 1 (bb nil "@(delay 1)"))))
  (testing "the clojure.lang.MapEntry constructor works"
    (is (true? (bb nil "(= (first {1 2}) (clojure.lang.MapEntry. 1 2))")))))

(deftest clojure-data-xml-test
  (is (= "<?xml version=\"1.0\" encoding=\"UTF-8\"?><items><item>1</item><item>2</item></items>"
         (bb nil "(let [xml (xml/parse-str \"<items><item>1</item><item>2</item></items>\")] (xml/emit-str xml))")))
  (is (= "0.0.87-SNAPSHOT" (bb nil "examples/pom_version_get.clj" (.getPath (io/file "test-resources" "pom.xml")))))
  (is (= ":xmlns.DAV%3A/propfind"
         (bb nil "(clojure.data.xml/alias-uri :D \"DAV:\") (str ::D/propfind)"))))

(deftest uberscript-test
  (let [tmp-file (java.io.File/createTempFile "uberscript" ".clj")]
    (.deleteOnExit tmp-file)
    (is (empty? (bb nil "--uberscript" (test-utils/escape-file-paths (.getPath tmp-file)) "-e" "(System/exit 1)")))
    (is (= "(System/exit 1)" (slurp tmp-file)))))

(deftest unrestricted-access
  (testing "babashka is allowed to mess with built-in vars"
    (is (= {} (bb nil "
(def assoc2 assoc) (alter-var-root #'clojure.core/assoc (constantly dissoc))
(let [res (assoc {:a 1} :a 2)]
  (alter-var-root #'clojure.core/assoc (constantly assoc2))
  res)")))))

(deftest pprint-test
  (testing "writer"
    (is (string? (bb nil "(let [sw (java.io.StringWriter.)] (clojure.pprint/pprint (range 10) sw) (str sw))"))))
  (testing "*print-right-margin*"
    (is (= "(0\n 1\n 2\n 3\n 4\n 5\n 6\n 7\n 8\n 9)\n"
           (bb nil "
(let [sw (java.io.StringWriter.)]
  (binding [clojure.pprint/*print-right-margin* 5]
    (clojure.pprint/pprint (range 10) sw)) (str sw))")))
    (is (= "(0 1 2 3 4 5 6 7 8 9)\n"
           (bb nil "
(let [sw (java.io.StringWriter.)]
  (binding [clojure.pprint/*print-right-margin* 50]
    (clojure.pprint/pprint (range 10) sw)) (str sw))"))))
  (testing "print-table writes to sci/out"
    (is (str/includes? (test-utils/bb "(with-out-str (clojure.pprint/print-table [{:a 1} {:a 2}]))") "----")))
  (testing "cl-format outputs"
    (testing "cl-format true writes to sci/out"
      (is (= "[1, 2, 3]" (bb nil "(with-out-str (clojure.pprint/cl-format true \"~<[~;~@{~w~^, ~:_~}~;]~:>\" [1,2,3]))"))))
    (testing "cl-format nil returns a string"
      (is (= "forty-two" (bb nil "(clojure.pprint/cl-format nil \"~R\" 42)"))))
    (testing "cl-format with a writer uses the writer"
      (is (= "1,234,567      " (bb nil "
(let [sw (java.io.StringWriter.)]
   (clojure.pprint/cl-format sw \"~15@<~:d~>\" 1234567)
   (str sw))")))))
  (testing "formatter-out"
    (is (= "[1, 2, 3]\n"
           (bb nil (pr-str '(do (require '[clojure.pprint :as pprint])
                                (def print-array (pprint/formatter-out "~<[~;~@{~w~^, ~:_~}~;]~:>"))
                                (pprint/with-pprint-dispatch
                                  #(if (seqable? %)
                                     (print-array %)
                                     (print %))
                                  (with-out-str (pprint/pprint [1 2 3]))))))))))

(deftest read-string-test
  (testing "namespaced keyword via alias"
    (is (= :clojure.string/foo
           (bb nil "(ns foo (:require [clojure.string :as str])) (read-string \"::str/foo\")")))))

(deftest available-stream-test
  (is (= 0 (bb nil "(.available System/in)"))))

(deftest ^:skip-windows file-reader-test
  (when (str/includes? (str/lower-case (System/getProperty "os.name")) "linux")
    (let [v (bb nil "(slurp (io/reader (java.io.FileReader. \"/proc/loadavg\")))")]
      (prn "output:" v)
      (is v))))

(deftest win-file-reader-test
  (let [v (bb nil "(slurp (io/reader (java.io.FileReader. \"test-resources/babashka/empty.clj\")))")]
    (prn "output:" v)
    (is (empty? v))))

(deftest ^:skip-windows download-and-extract-test
  ;; Disabled because Github throttles bandwidth and this makes for a very slow test.
  ;; TODO: refactor into individual unit tests
  ;; One for downloading a small file and one for unzipping.
  #_(is (try (= 6 (bb nil (io/file "test" "babashka" "scripts" "download_and_extract_zip.bb")))
             (catch Exception e
               (is (str/includes? (str e) "timed out"))))))

(deftest get-message-on-exception-info-test
  (is "foo" (bb nil "(try (throw (ex-info \"foo\" {})) (catch Exception e (.getMessage e)))")))

(deftest pushback-reader-test
  (is (= "foo" (bb nil "
(require '[clojure.java.io :as io])
(let [pb (java.io.PushbackInputStream. (java.io.ByteArrayInputStream. (.getBytes \"foo\")))]
  (.unread pb (.read pb))
  (slurp pb))"))))

(deftest delete-on-exit-test
  (when test-utils/native?
    (let [f (java.io.File/createTempFile "foo" "bar")
          p (test-utils/escape-file-paths (.getPath f))]
      (bb nil (format "(.deleteOnExit (io/file \"%s\"))" p))
      (is (false? (.exists f))))))

(deftest yaml-test
  (is (str/starts-with?
       (bb nil "(yaml/generate-string [{:name \"John Smith\", :age 33} {:name \"Mary Smith\", :age 27}])")
       "-")))

(deftest arrays-copy-of-test
  (is (= "foo" (bb nil "(String. (java.util.Arrays/copyOf (.getBytes \"foo\") 3))"))))

(deftest data-readers-test
  (is (= 2 (bb nil "(set! *data-readers* {'t/tag inc}) #t/tag 1"))))

(deftest ordered-test
  (is (= (ordered-map :a 1 :b 2) (bb nil "(flatland.ordered.map/ordered-map :a 1 :b 2)"))))

(deftest data-diff-test
  (is (= [[nil 1] [nil 2] [1 nil 2]] (bb nil "(require '[clojure.data :as d]) (d/diff [1 1 2] [1 2 2])"))))

(deftest version-property-test
  (is (= "true\ntrue\nfalse\n"
         (test-utils/bb nil (.getPath (io/file "test-resources" "babashka" "version.clj"))))))

(defmethod clojure.test/assert-expr 'working? [msg form]
  (let [body (next form)]
    `(do ~@body
         (clojure.test/do-report {:type :pass, :message ~msg,
                                  :expected :success, :actual :success}))))

(deftest empty-expressions-test
  (testing "bb executes the empty file and doesn't start a REPL"
    (is (working? (test-utils/bb nil (.getPath (io/file "test-resources" "babashka" "empty.clj"))))))
  (testing "bb executes the empty expression and doesn't start a REPL"
    (is (working? (test-utils/bb nil "-e" "")))))

(deftest config-property-test
  (is (= "true\ntrue\n"
         (test-utils/with-config {:tasks {}}
           (test-utils/bb nil (.getPath (io/file "test-resources" "babashka" "config_property.clj"))))))
  (is (= "false\n"
         (test-utils/bb nil (.getPath (io/file "test-resources" "babashka" "config_property.clj"))))))

(deftest file-property-test
  (is (= "true\nfalse\n"
         (test-utils/bb nil (.getPath (io/file "test-resources" "babashka" "file_property1.clj")))))
  (is (= "true\n"
         (test-utils/bb nil (.getPath (io/file "test-resources" "babashka" "file_property2.clj")))))
  (is (apply =
             (bb nil (.getPath (io/file "test" "babashka" "scripts" "simple_file_var.bb")))))
  (let [res (bb nil (.getPath (io/file "test" ".." "test" "babashka"
                                       "scripts" "simple_file_var.bb")))]
    (is (apply = res))
    (is (str/includes? (first res) ".."))))

(deftest file-location-test
  (is (thrown-with-msg?
       Exception #"file_location2.clj"
       (test-utils/bb nil (.getPath (io/file "test-resources" "babashka" "file_location1.clj"))))))

(deftest preloads-file-location-test
  (when (System/getenv "BABASHKA_PRELOADS_TEST")
    (is (thrown-with-msg?
         Exception #"preloads"
         (test-utils/bb nil (.getPath (io/file "test-resources" "babashka" "file_location_preloads.clj")))))))

(deftest repl-test
  (is (str/includes? (test-utils/bb "(ns foo) ::foo" "--repl") ":foo/foo"))
  (is (str/includes? (test-utils/bb "[*warn-on-reflection* (set! *warn-on-reflection* true) *warn-on-reflection*]")
                     "[false true true]"))
  (when-not test-utils/native?
    (let [sw (java.io.StringWriter.)]
      (sci/with-bindings {sci/err sw}
        (test-utils/bb {:in "x" :err sw} "--repl"))
      (is (str/includes? (str sw) "Could not resolve symbol: x [at <repl>:1:1]")))))

(deftest java-stream-test
  (is (every? number? (bb nil "(take 2 (iterator-seq (.iterator (.doubles (java.util.Random.)))))"))))

(deftest read+string-test
  (is (= '[:user/foo "::foo"]
         (bb nil "(read+string (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. \"::foo\")))"))))

(deftest iterable-test
  (is (true? (bb nil "
(defn iter [coll]
  (if (instance? java.lang.Iterable coll)
    (.iterator ^java.lang.Iterable coll)
    (let [s (or (seq coll) [])]
      (.iterator ^java.lang.Iterable s))))

(= [1 2 3] (iterator-seq (iter [1 2 3])))"))))

(deftest var-print-method-test
  (when test-utils/native?
    (is (bb nil "(defmethod print-method sci.lang.IVar [o w] (.write w (str :foo (symbol o)))) (def x 1) (= \":foouser/x\" (pr-str #'x))"))
    (is (= :foouser/x (bb nil "(defmethod print-method sci.lang.IVar [o w] (.write w (str :foo (symbol o)))) (def x 1)")))))

(deftest stdout-interop-test
  (when test-utils/native?
    (is (= 'Something (bb nil "(.print (System/out) \"Something\")")))))

(deftest byte-buffer-test
  (testing "interop with HeapByteBuffer"
    (is (= 42 (bb nil "(count (.array (java.nio.ByteBuffer/allocate 42)))"))))
  (testing "interop with HeapByteByfferR"
    (is (bb nil "(.hasRemaining (.asReadOnlyBuffer (java.nio.ByteBuffer/allocate 42)))")))
  (is (bb nil "
(import 'java.io.RandomAccessFile)
(import 'java.nio.channels.FileChannel$MapMode)
(def raf (RandomAccessFile. \"/tmp/binf-example.dat\" \"rw\"))
;; DirectByteBuffer
(def view (-> raf .getChannel (.map FileChannel$MapMode/READ_WRITE 0 4)))
;; interop with DirectByteBuffer
(.load view)
(.force view)
true"))
  (is (bb nil "
(import 'java.io.RandomAccessFile)
(import 'java.nio.channels.FileChannel$MapMode)
(def raf (RandomAccessFile. \"/tmp/binf-example.dat\" \"r\"))
;; DirectByteBuffer
(def view (-> raf .getChannel (.map FileChannel$MapMode/READ_ONLY 0 4)))
;; interop with DirectByteBufferR
(.load view)
(.force view)
true")))

(deftest secure-random-test
  (let [prog '(do (import 'java.security.SecureRandom 'java.util.Base64)

                  (let [random (SecureRandom.)
                        base64 (.withoutPadding (Base64/getUrlEncoder))]
                    (defn generate-token []
                      (let [buffer (byte-array 32)]
                        (.nextBytes random buffer)
                        (.encodeToString base64 buffer))))
                  (generate-token))]
    (is (string? (bb nil (str prog))))))

(deftest with-precision-test
  (is (= 0.33333333333333333333M (bb nil "(with-precision 20 (/ 1M 3))")))
  (is (= 0.33333333333333333334M (bb nil "(with-precision 20 :rounding CEILING (/ 1M 3))"))))

(deftest doc-test
  (test-utils/with-config {:paths ["test-resources/task_scripts"]}
    (is (str/includes? (apply test-utils/bb nil
                              (map str ["doc" "tasks"]))
                       "This is task ns docstring."))
    (is (str/includes? (apply test-utils/bb nil
                              (map str ["doc" "tasks/foo"]))
                       "Foo docstring"))
    (is (str/includes? (apply test-utils/bb nil
                              (map str ["doc" "tasks/-main"]))
                       "Main docstring"))
    (is (str/includes? (apply test-utils/bb nil
                              (map str ["doc" "with-precision"]))
                       "precision"))
    (is (str/blank? (with-out-str (main/main "doc" "non-existing"))))
    (is (= 1 (main/main "doc" "non-existing")))))

(deftest ^:skip-windows process-handler-info-test
  (when test-utils/native?
    (is (= ["-e" "(vec (.get (.arguments (.info (java.lang.ProcessHandle/current)))))"]
           (bb nil "-e" "(vec (.get (.arguments (.info (java.lang.ProcessHandle/current)))))")))
    (is (str/ends-with?
         (bb nil "-e" "(.get (.command (.info (java.lang.ProcessHandle/current))))")
         "bb"))))

(deftest ^:windows-only win-process-handler-info-test
  (when (and test-utils/native? main/windows?)
    (is (str/ends-with?
          (bb nil "-e" "(.get (.command (.info (java.lang.ProcessHandle/current))))")
          "bb.exe"))))

(deftest interop-concurrency-test
  (is (= ["true" 3] (last (bb nil "-e"
                              "
(def f (fn [_]
         [(String/valueOf true)
          (.length \"foo\")]))

(vec (pmap f (map str (range 10000))))")))))

(deftest print-readably-test
  (is (= "\"foo\"" (bb nil "-e" "(binding [*print-readably* true] (pr-str \"foo\"))")))
  (is (= "foo" (bb nil "-e" "(binding [*print-readably* false] (pr-str \"foo\"))")))
  (is (= "foo\n" (bb nil "-e" "(binding [*print-readably* false] (with-out-str (clojure.pprint/pprint \"foo\")))"))))

; repl-requires: '[[clojure.repl :refer (source apropos pst dir doc find-doc)]
;                  [clojure.pprint :refer (pp pprint)]]
(deftest repl-requires-test
  (testing "the elements of repl-requires are available to scripts passed on the command line"
    (is (str/includes? (bb nil "
    (load-file \"test-resources/babashka/file_location2.clj\")
    (require '[babashka.file-location2 :as fl])
    (source fl/ok)") "ok"))
    ; using <= in case new matching functions get added
    (is (<= 8 (bb nil '(count (apropos "first")))))
    (is (= [1 2 3] (bb "[1 2 3]" "(pprint *input*)")))
    (let [first-doc (test-utils/bb nil "(doc first)")]
        (is (every? #(str/includes? first-doc %) ["---" "clojure.core/first" "first item"])))))

(deftest edn-input-test
  (testing "clojure's default readers"
    (is (= '(#inst "2021-08-24T00:56:02.014-00:00")
          (bb "#inst \"2021-08-24T00:56:02.014-00:00\"" "-I" "(println *input*)")))
    (is (= '(#uuid "00000000-0000-0000-0000-000000000000")
          (bb "#uuid \"00000000-0000-0000-0000-000000000000\"" "-I" "(println *input*)"))))
  (testing "use tagged-literal as default data reader fn..."
    (testing "when using the -I option"
      (is (= "(#made-up-tag 42)\n"
            (test-utils/normalize (test-utils/bb "#made-up-tag 42" "-I" "(println *input*)"))))
      (is (= "(#abc 123 #cde 789)\n"
            (test-utils/normalize (test-utils/bb "{:a #abc 123}{:a #cde 789}" "-I" "(map :a *input*)")))))
    (testing "when using --stream and -I"
      (is (= "#abc 123\n#cde 789\n"
            (test-utils/normalize (test-utils/bb "{:a #abc 123}{:a #cde 789}" "--stream" "-I" "-e" "(println (:a *input*))")))))
    (testing "when using --stream (-I is sort of implied if no -i)"
      (is (= "#abc 123\n#cde 789\n"
            (test-utils/normalize (test-utils/bb "{:a #abc 123}{:a #cde 789}" "--stream" "-e" "(println (:a *input*))")))))
    (testing "when reading one EDN form from stdin (no --stream or -I or -i)"
      (is (= "#abc 123\n"
             (test-utils/normalize (test-utils/bb "{:a #abc 123}{:a #cde 789}" "-e" "(println (:a *input*))")))))))

(deftest piped-input-output-stream-test
  (is (= 10 (bb nil "
(def po (java.io.PipedOutputStream.))
(def pi (java.io.PipedInputStream.))
(.connect pi po)
(.write po 10)
(.read pi)
"))))

(deftest InetAddress-test
  (is (= "192.168.2.2" (bb nil "(-> (java.net.InetAddress/getByName \"192.168.2.2\") (.getHostAddress))"))))

(deftest satisfies-protocols-test
  (is (true? (bb nil "(satisfies? clojure.core.protocols/Datafiable {})")))
  (is (true? (bb nil "(satisfies? clojure.core.protocols/Navigable {})")))
  (is (true? (bb nil "(satisfies? clojure.core.protocols/IKVReduce {})"))))

(deftest interop-on-proxy
  (is (true? (bb nil (pr-str
                      '(instance? java.net.PasswordAuthentication
                                  (.getPasswordAuthentication
                                   (proxy [java.net.Authenticator] []
                                     (getPasswordAuthentication []
                                       (java.net.PasswordAuthentication. "bork"
                                                                         (char-array "dude")))))))))))

(deftest aget-test
  (is (= 1 (bb nil "(def array-2d (into-array [(int-array [1 2]) (int-array [3 4])])) (aget array-2d 0 0)"))))

(deftest into-array-fallback-test
  (is (= :f (bb nil "(first (into-array [:f]))")))
  (is (= :f (bb nil "(first (first (into-array [(into-array [:f])])))"))))

(deftest var-names-test
  (testing "for all public vars, ns/symbol from ns map matches metadata"
    (is (empty? (bb nil "-f" "test-resources/babashka/check_var_names.bb")))))

;;;; Scratch

(comment
  (dotimes [_ 10] (wait-for-port-test)))
