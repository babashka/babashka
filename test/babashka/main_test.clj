(ns babashka.main-test
  (:require
   [babashka.main :as main]
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is testing]]
   [sci.core :as sci]))

(defn bb [input & args]
  (edn/read-string (apply test-utils/bb (when (some? input) (str input)) (map str args))))

(deftest parse-opts-test
  (is (= 123 (bb nil "(println 123)")))
  (is (= 123 (bb nil "-e" "(println 123)")))
  (is (= 123 (bb nil "--eval" "(println 123)")))
  (testing "distinguish automatically between expression or file name"
    (is (= {:result 8080} (bb nil "test/babashka/scripts/tools.cli.bb")))
    (is (thrown-with-msg? Exception #"does not exist" (bb nil "foo.clj")))
    (is (thrown-with-msg? Exception #"does not exist" (bb nil "-help"))))
  (is (= "1 2 3" (bb nil "-e" "(require '[clojure.string :as str1])" "-e" "(str1/join \" \" [1 2 3])")))
  (is (= '("-e" "1") (bb nil "-e" "*command-line-args*" "--" "-e" "1"))))

(deftest print-error-test
  (is (thrown-with-msg? Exception #"java.lang.NullPointerException"
                        (bb nil "(subs nil 0 0)"))))

(deftest main-test
  (testing "-io behaves as identity"
    (= "foo\nbar\n" (test-utils/bb "foo\nbar\n" "-io" "*input*")))
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
            (bb "(keep #(when (re-find #\"(?i)clojure\" (second %)) (first %)) *input*)"))))))

(deftest println-test
  (is (= "hello\n" (test-utils/bb nil "(println \"hello\")"))))

(deftest input-test
  (testing "bb doesn't wait for input if *input* isn't used"
    (is (= "2\n" (with-out-str (main/main "(inc 1)"))))))

(deftest System-test
  (let [res (bb nil "-f" "test/babashka/scripts/System.bb")]
    (is (= "bar" (second res)))
    (doseq [s res]
      (is (not-empty s))))
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        exit-code (sci/with-bindings {sci/out out
                                      sci/err err}
                    (binding [*out* out *err* err]
                      (main/main "--time" "(println \"Hello world!\") (System/exit 42)")))]
    (is (= (str out) "Hello world!\n"))
    (is (re-find #"took" (str err)))
    (is (= 42 exit-code))))

(deftest malformed-command-line-args-test
  (is (thrown-with-msg? Exception #"File does not exist: non-existing\n"
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
    (spit tmp "(defn foo [x y] (+ x y)) (defn bar [x y] (* x y))")
    (is (= "120\n" (test-utils/bb nil (format "(load-file \"%s\") (bar (foo 10 30) 3)"
                                              (.getPath tmp)))))))

(deftest eval-test
  (is (= "120\n" (test-utils/bb nil "(eval '(do (defn foo [x y] (+ x y))
                                                (defn bar [x y] (* x y))
                                                (bar (foo 10 30) 3)))"))))

(deftest preloads-test
  ;; THIS TEST REQUIRES:
  ;; export BABASHKA_PRELOADS='(defn __bb__foo [] "foo") (defn __bb__bar [] "bar")'
  (when (System/getenv "BABASHKA_PRELOADS_TEST")
    (is (= "foobar" (bb nil "(str (__bb__foo) (__bb__bar))")))))

(deftest io-test
  (is (true? (bb nil "(.exists (io/file \"README.md\"))")))
  (is (true? (bb nil "(.canWrite (io/file \"README.md\"))"))))

(deftest pipe-test
  (when test-utils/native?
    (let [out (:out (sh "bash" "-c" "./bb -o '(range)' |
                         ./bb --stream '(* *input* *input*)' |
                         head -n10"))
          out (str/split-lines out)
          out (map edn/read-string out)]
      (is (= (take 10 (map #(* % %) (range))) out))))
  (when test-utils/native?
    (let [out (:out (sh "bash" "-c" "./bb -O '(repeat \"dude\")' |
                         ./bb --stream '(str *input* \"rino\")' |
                         ./bb -I '(take 3 *input*)'"))
          out (edn/read-string out)]
      (is (= '("duderino" "duderino" "duderino") out)))))

(deftest lazy-text-in-test
  (when test-utils/native?
    (let [out (:out (sh "bash" "-c" "yes | ./bb -i '(take 2 *input*)'"))
          out (edn/read-string out)]
      (is (= '("y" "y") out)))))

(deftest future-test
  (is (= 6 (bb nil "@(future (+ 1 2 3))"))))

(deftest process-builder-test
  (is (str/includes? (bb nil "
(def ls (-> (ProcessBuilder. [\"ls\"]) (.start)))
(def input (.getOutputStream ls))
(.write (io/writer input) \"hello\") ;; dummy test just to see if this works
(def output (.getInputStream ls))
(assert (int? (.waitFor ls)))
(slurp output)")
                     "LICENSE")))

(deftest create-temp-file-test
  (let [temp-dir-path (System/getProperty "java.io.tmpdir")]
    (is (= true
           (bb nil (format "(let [tdir (io/file \"%s\")
                                 tfile
                                 (File/createTempFile \"ctf\" \"tmp\" tdir)]
                             (.deleteOnExit tfile) ; for cleanup
                             (.exists tfile))"
                           temp-dir-path))))))

(deftest wait-for-port-test
  (let [server (test-utils/start-server! 1777)]
    (is (= 1777 (:port (bb nil "(wait/wait-for-port \"127.0.0.1\" 1777)"))))
    (test-utils/stop-server! server)
    (is (= :timed-out (bb nil "(wait/wait-for-port \"127.0.0.1\" 1777 {:default :timed-out :timeout 50})"))))
  (let [edn (bb nil (io/file "test" "babashka" "scripts" "socket_server.bb"))]
    (is (= "127.0.0.1" (:host edn)))
    (is (=  1777 (:port edn)))
    (is (number? (:took edn)))))

(deftest wait-for-path-test
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

(deftest async-test
  (is (= "process 2\n" (test-utils/bb nil "
   (defn async-command [& args]
     (async/thread (apply shell/sh \"bash\" \"-c\" args)))

   (-> (async/alts!! [(async-command \"sleep 2 && echo process 1\")
                      (async-command \"sleep 1 && echo process 2\")])
     first :out str/trim println)"))))

(deftest tools-cli-test
  (is (= {:result 8080} (bb nil "test/babashka/scripts/tools.cli.bb"))))

(deftest try-catch-test
  (is (zero? (bb nil "(try (/ 1 0) (catch ArithmeticException _ 0))"))))

(deftest reader-conditionals-test
  (is (= :hello (bb nil "#?(:clj (in-ns 'foo)) (println :hello)")))
  (is (= :hello (bb nil "#?(:bb :hello :default :bye)"))))

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
        path (.getPath tmp-file)]
    (bb nil (format "(with-open [w (io/writer \"%s\")]
                       (.write w \"foobar\n\")
                       (.append w \"barfoo\n\")
                       nil)"
                    path))
    (is (= "foobar\nbarfoo\n" (slurp path)))))

(deftest binding-test
  (is (=  6 (bb nil "(def w (java.io.StringWriter.))
                 (binding [clojure.core/*out* w]
                   (println \"hello\"))
                 (count (str w))"))))

(deftest with-out-str-test
  (is (= 6 (bb nil "(count (with-out-str (println \"hello\")))"))))

(deftest with-in-str-test
  (is (= 5 (bb nil "(count (with-in-str \"hello\" (read-line)))"))))

(deftest java-nio-test
  (let [f (java.io.File/createTempFile "foo" "bar")
        temp-path (.getPath f)
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
    (is (.exists f2))))

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
  (is (= 1 (bb nil "(binding [*command-line-args* 1] *command-line-args*)")))
  (is (= 1 (bb nil "(binding [*input* 1] *input*)"))))

(deftest file-in-error-msg-test
  (is (thrown-with-msg? Exception #"error.bb"
                        (bb nil (.getPath (io/file "test" "babashka" "scripts" "error.bb"))))))

(deftest compatibility-test
  (is (true? (bb nil "(set! *warn-on-reflection* true)"))))

(deftest clojure-main-repl-test
  (is (= "\"> foo!\\nnil\\n> \"\n" (test-utils/bb nil "
(defn foo [] (println \"foo!\"))
(with-out-str
  (with-in-str \"(foo)\"
    (clojure.main/repl :init (fn []) :prompt (fn [] (print \"> \")))))"))))

(deftest command-line-args-test
  (is (true? (bb nil "(nil? *command-line-args*)")))
  (is (= ["1" "2" "3"] (bb nil "*command-line-args*" "1" "2" "3"))))

(deftest need-constructors-test
  (testing "the clojure.lang.Delay constructor works"
    (is (= 1 (bb nil "@(delay 1)"))))
  (testing "the clojure.lang.MapEntry constructor works"
    (is (true? (bb nil "(= (first {1 2}) (clojure.lang.MapEntry. 1 2))")))))

(deftest uberscript-test
  (let [tmp-file (java.io.File/createTempFile "uberscript" ".clj")]
    (.deleteOnExit tmp-file)
    (is (empty? (bb nil "--uberscript" (.getPath tmp-file) "-e" "(System/exit 1)")))
    (is (= "(System/exit 1)" (slurp tmp-file)))))

;;;; Scratch

(comment
  (dotimes [_ 10] (wait-for-port-test))
  )
