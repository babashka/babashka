(ns babashka.main-test
  (:require
   [babashka.main :as main]
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is testing]]))

(defn bb [input & args]
  (edn/read-string (apply test-utils/bb (str input) (map str args))))

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
        exit-code (binding [*out* out *err* err]
                    (main/main "--time" "(println \"Hello world!\") (System/exit 42)"))]
    (is (= (str out) "Hello world!\n"))
    (is (re-find #"took" (str err)))
    (is (= 42 exit-code))))

(deftest malformed-command-line-args-test
  (is (thrown-with-msg? Exception #"File does not exist: non-existing\n"
                        (bb nil "-f" "non-existing")))
  (testing "no arguments prints help"
    (is (str/includes?
         (try (test-utils/bb nil)
              (catch clojure.lang.ExceptionInfo e
                (:stdout (ex-data e))))
         "Usage:"))))

(deftest ssl-test
  (let [graalvm-home (System/getenv "GRAALVM_HOME")
        lib-path (format "%1$s/jre/lib:%1$s/jre/lib/amd64" graalvm-home)
        ;; _ (prn "lib-path" lib-path)
        resp (bb nil (format "(System/setProperty \"java.library.path\" \"%s\")
                              (slurp \"https://www.google.com\")"
                             lib-path))]
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

(deftest preloads-test
  ;; THIS TEST REQUIRES:
  ;; export BABASHKA_PRELOADS='(defn __bb__foo [] "foo") (defn __bb__bar [] "bar")'
  (is (= "foobar" (bb nil "(str (__bb__foo) (__bb__bar))"))))

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

(deftest wait-for-it-test
  (is (thrown-with-msg?
       Exception
       #"timeout"
       (bb nil "(def web-server (conch/proc \"python\" \"-m\" \"SimpleHTTPServer\" \"7171\"))
                (net/wait-for-it \"127.0.0.1\" 7171)
                (conch/destroy web-server)
                (net/wait-for-it \"localhost\" 7172 {:timeout 50})"))))

(deftest async-test
  (is (= "process 2\n" (test-utils/bb nil "
   (defn async-command [& args]
     (async/thread (apply shell/sh \"bash\" \"-c\" args)))

   (-> (async/alts!! [(async-command \"sleep 2 && echo process 1\")
                      (async-command \"sleep 1 && echo process 2\")])
     first :out str/trim println)"))))

(deftest tools-cli-test
  (is (= {:result 8080} (bb nil "test/babashka/scripts/tools.cli.bb"))))
