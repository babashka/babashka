(ns babashka.classpath-test
  (:require
   [babashka.test-utils :as tu]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is testing]]))

(defn bb [input & args]
  (edn/read-string (apply tu/bb (when (some? input) (str input)) (map str args))))

(def path-sep (System/getProperty "path.separator"))

(deftest classpath-test
  (is (= :my-script/bb
         (bb nil "--prn" "--classpath" "test-resources/babashka/src_for_classpath_test"
             "(require '[my-script :as ms]) (ms/foo)")))
  (is (= "hello from foo\n"
         (tu/bb nil "--prn" "--classpath" "test-resources/babashka/src_for_classpath_test/foo.jar"
                "(require '[foo :as f]) (f/foo)")))
  (is (thrown-with-msg? Exception #"not find"
         (tu/bb nil
                "(require '[foo :as f])"))))

(deftest babashka-classpath-test
  (is (= "test-resources"
         (bb nil "--classpath" "test-resources"
             "(require '[babashka.classpath :as cp]) (cp/get-classpath)")))
  (is (= (str/join path-sep ["test-resources" "foobar"])
        (bb nil "--classpath" "test-resources"
            "(require '[babashka.classpath :as cp]) (cp/add-classpath \"foobar\") (cp/get-classpath)")))
  (is (= ["foo" "bar"]
         (bb nil "--classpath" (str/join path-sep ["foo" "bar"])
           "(require '[babashka.classpath :as cp]) (cp/split-classpath (cp/get-classpath))"))))

(deftest classpath-env-test
  ;; for this test you have to set `BABASHKA_CLASSPATH` to test-resources/babashka/src_for_classpath_test/env
  ;; and `BABASHKA_PRELOADS` to "(require '[env-ns])"
  (when (System/getenv "BABASHKA_CLASSPATH_TEST")
    (println (System/getenv "BABASHKA_CLASSPATH"))
    (is (= "env!" (bb nil "(env-ns/foo)")))))

(deftest main-test
  (is (= "(\"1\" \"2\" \"3\" \"4\")\n"
         (tu/bb nil "--prn" "--classpath" "test-resources/babashka/src_for_classpath_test" "-m" "my.main" "1" "2" "3" "4")))
  (testing "system property"
    (is (= "\"my.main2\""
           (str/trim (tu/bb nil "--prn" "--classpath" "test-resources/babashka/src_for_classpath_test" "-m" "my.main2"))))))

(deftest error-while-loading-test
  (is (true?
         (bb nil "--classpath" "test-resources/babashka/src_for_classpath_test"
                "
(try
  (require '[ns-with-error])
  (catch Exception nil))
(nil? (resolve 'ns-with-error/x))"))))

(deftest resource-test
  (let [tmp-file (java.io.File/createTempFile "icon" ".png")]
    (.deleteOnExit tmp-file)
    (bb nil "--classpath" "logo" "-e" (format "(io/copy (io/input-stream (io/resource \"icon.png\")) (io/file \"%s\"))"
                                        (tu/escape-file-paths (.getPath tmp-file))))
    (is (= (.length (io/file "logo" "icon.png"))
           (.length tmp-file))))
  (testing "No exception on absolute path"
    (is (nil? (bb nil "(io/resource \"/tmp\")"))))
  (testing "Reading a resource from a .jar file"
    (is (= "true"
           (str/trim
            (tu/bb nil "--classpath" "test-resources/babashka/src_for_classpath_test/foo.jar"
                   "(pos? (count (slurp (io/resource \"foo.clj\")))) "))))))

(deftest classloader-test
  (let [url
        (tu/bb nil "--classpath" "test-resources/babashka/src_for_classpath_test/foo.jar"
               "(first (map str (.getURLs (clojure.lang.RT/baseLoader))))")]
    (is (str/includes? url "file:"))
    (is (str/includes? url "foo.jar"))))
