(ns babashka.main-test
  (:require
   [babashka.main :as main]
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is testing]]
   [clojure.java.io :as io]
   [sci.core :as sci]))

(defn bb [input & args]
  (edn/read-string (apply test-utils/bb (when (some? input) (str input)) (map str args))))

(deftest parse-opts-test
  (is (= {:expression "(println 123)"}
         (main/parse-opts ["-e" "(println 123)"])))

  (is (= {:expression "(println 123)"}
         (main/parse-opts ["--eval" "(println 123)"])))

  (testing "distinguish automatically between expression or file name"
    (is (= {:expression "(println 123)"
            :command-line-args []}
           (main/parse-opts ["(println 123)"])))

    (is (= {:file "src/babashka/main.clj"
            :command-line-args []}
           (main/parse-opts ["src/babashka/main.clj"])))

    (is (= {:expression "does-not-exist"
            :command-line-args []}
           (main/parse-opts ["does-not-exist"])))))

(deftest main-test
  (testing "-io behaves as identity"
    (= "foo\nbar\n" (test-utils/bb "foo\nbar\n" "-io" "*in*")))
  (testing "if and when"
    (is (= 1 (bb 0 '(if (zero? *in*) 1 2))))
    (is (= 2 (bb 1 '(if (zero? *in*) 1 2))))
    (is (= 1 (bb 0 '(when (zero? *in*) 1))))
    (is (nil? (bb 1 '(when (zero? *in*) 1)))))
  (testing "and and or"
    (is (= false (bb 0 '(and false true *in*))))
    (is (= 0 (bb 0 '(and true true *in*))))
    (is (= 1 (bb 1 '(or false false *in*))))
    (is (= false (bb false '(or false false *in*))))
    (is (= 3 (bb false '(or false false *in* 3)))))
  (testing "fn"
    (is (= 2 (bb 1 "(#(+ 1 %) *in*)")))
    (is (= [1 2 3] (bb 1 "(map #(+ 1 %) [0 1 2])")))
    (is (= 1 (bb 1 "(#(when (odd? *in*) *in*))"))))
  (testing "map"
    (is (= [1 2 3] (bb 1 '(map inc [0 1 2])))))
  (testing "keep"
    (is (= [false true false] (bb 1 '(keep odd? [0 1 2])))))
  (testing "->"
    (is (= 4 (bb 1 '(-> *in* inc inc (inc))))))
  (testing "->>"
    (is (= 10 (edn/read-string (test-utils/bb "foo\n\baar\baaaaz" "-i" "(->> *in* (map count) (apply max))")))))
  (testing "literals"
    (is (= {:a 4
            :b {:a 2}
            :c [1 1]
            :d #{1 2}}
           (bb 1 '{:a (+ 1 2 *in*)
                   :b {:a (inc *in*)}
                   :c [*in* *in*]
                   :d #{*in* (inc *in*)}}))))
  (testing "shuffle the contents of a file"
    (let [in "foo\n Clojure is nice. \nbar\n If you're nice to clojure. "
          in-lines (set (str/split in #"\n"))
          out (test-utils/bb in
                             "-io"
                             (str '(shuffle *in*)))
          out-lines (set (str/split out #"\n"))]
      (is (= in-lines out-lines))))
  (testing "find occurrences in file by line number"
    (is (= '(1 3)
           (->
            (bb "foo\n Clojure is nice. \nbar\n If you're nice to clojure. "
                "-i"
                "(map-indexed #(-> [%1 %2]) *in*)")
            (bb "(keep #(when (re-find #\"(?i)clojure\" (second %)) (first %)) *in*)"))))))

(deftest println-test
  (is (= "hello\n" (test-utils/bb nil "(println \"hello\")"))))

(deftest input-test
  (testing "bb doesn't wait for input if *in* isn't used"
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
  (is (= "2\n3\n4\n" (test-utils/bb "1 2 3" "--stream" "(inc *in*)")))
  (is (= "2\n3\n4\n" (test-utils/bb "{:x 2} {:x 3} {:x 4}" "--stream" "(:x *in*)")))
  (let [x "foo\n\bar\n"]
    (is (= x (test-utils/bb x "--stream" "-io" "*in*"))))
  (let [x "f\n\b\n"]
    (is (= x (test-utils/bb x "--stream" "-io" "(subs *in* 0 1)")))))

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
                         ./bb --stream '(* *in* *in*)' |
                         head -n10"))
          out (str/split-lines out)
          out (map edn/read-string out)]
      (is (= (take 10 (map #(* % %) (range))) out))))
  (when test-utils/native?
    (let [out (:out (sh "bash" "-c" "./bb -O '(repeat \"dude\")' |
                         ./bb --stream '(str *in* \"rino\")' |
                         ./bb -I '(take 3 *in*)'"))
          out (edn/read-string out)]
      (is (= '("duderino" "duderino" "duderino") out)))))

(deftest lazy-text-in-test
  (when test-utils/native?
    (let [out (:out (sh "bash" "-c" "yes | ./bb -i '(take 2 *in*)'"))
          out (edn/read-string out)]
      (is (= '("y" "y") out)))))

(deftest future-test
  (is (= 6 (bb nil "@(future (+ 1 2 3))"))))

(deftest conch-test
  (is (str/includes? (bb nil "(->> (conch/proc \"ls\") (conch/stream-to-string :out))")
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
  (is (= :timed-out
         (bb nil "(def web-server (conch/proc \"python\" \"-m\" \"SimpleHTTPServer\" \"7171\"))
                (wait/wait-for-port \"127.0.0.1\" 7171)
                (conch/destroy web-server)
                (wait/wait-for-port \"localhost\" 7172 {:default :timed-out :timeout 50})"))))

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
  (is (= java.util.regex.Pattern/CANON_EQ
         (bb nil "java.util.regex.Pattern/CANON_EQ"))))

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
    (is (= "hello"  (bb nil "@(future (prn \"hello\"))"))))

  )
