(ns babashka.uberjar-test
  (:require
    [babashka.main :as main]
    [babashka.test-utils :as tu]
    [clojure.string :as str]
    [clojure.test :as t :refer [deftest is testing]]))

(defn count-entries [jar]
  (with-open [jar-file (java.util.jar.JarFile. jar)]
    (count (map #_prn
                identity
                (enumeration-seq
                 (.entries jar-file))))))

(deftest uberjar-test
  (testing "uberjar with --main"
    (let [tmp-file (java.io.File/createTempFile "uber" ".jar")
          path (.getPath tmp-file)]
      (.deleteOnExit tmp-file)
      (tu/bb nil "--classpath" "test-resources/babashka/uberjar/src" "uberjar" path "-m" "my.main-main")
      (is (= "(\"1\" \"2\" \"3\" \"4\")\n"
             (tu/bb nil "--jar" path "1" "2" "3" "4")))
      (is (= "(\"1\" \"2\" \"3\" \"4\")\n"
             (tu/bb nil "-jar" path "1" "2" "3" "4")))
      (is (= "(\"1\" \"2\" \"3\" \"4\")\n"
             (tu/bb nil path "1" "2" "3" "4")))))
  (testing "without main, a REPL starts"
    ;; NOTE: if we choose the same tmp-file as above and doing this all in the
    ;; same JVM process, the below test fails because my.main-main will be the
    ;; main class in the manifest, even if we delete the tmp-file, which may
    ;; indicate a state-related bug somewhere!
    (let [tmp-file (java.io.File/createTempFile "uber" ".jar")
          path (.getPath tmp-file)]
      (.deleteOnExit tmp-file)
      (tu/bb nil "--classpath" "test-resources/babashka/uberjar/src" "uberjar" path)
      (is (str/includes? (tu/bb "(+ 1 2 3)" path) "6"))))
  (testing "use bb.edn classpath when no other --classpath"
    (tu/with-config {:paths ["test-resources/babashka/uberjar/src"]}
      (let [tmp-file (java.io.File/createTempFile "uber" ".jar")
            path (.getPath tmp-file)]
        (.deleteOnExit tmp-file)
        ;; building with no --classpath
        (tu/bb nil "uberjar" path "-m" "my.main-main")
        ;; running
        (is (= "(\"42\")\n" (tu/bb nil "--jar" path "-m" "my.main-main" "42")))
        (is (= "(\"42\")\n" (tu/bb nil "--classpath" path "-m" "my.main-main" "42")))
        (is (= "(\"42\")\n" (tu/bb nil path "42"))))))
  (testing "ignore empty entries on classpath"
    (let [tmp-file (java.io.File/createTempFile "uber" ".jar")
          path (.getPath tmp-file)
          empty-classpath (if main/windows? ";;;" ":::")]
      (.deleteOnExit tmp-file)
      (tu/bb nil "--classpath" empty-classpath "uberjar" path "-m" "my.main-main")
      ;; Only a manifest entry is added
      (is (< (count-entries path) 3)))))

(deftest throw-on-empty-classpath
  ;; this test fails the windows native test in CI
  (when-not main/windows?
    (testing "throw on empty classpath"
      (let [tmp-file (java.io.File/createTempFile "uber" ".jar")
            path     (.getPath tmp-file)]
        (.deleteOnExit tmp-file)
        (is (thrown-with-msg?
             Exception #"classpath"
             (tu/bb nil "uberjar" path "-m" "my.main-main")))))))
