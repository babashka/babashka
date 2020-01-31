(ns babashka.classpath-test
  (:require
   [babashka.test-utils :as tu]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :as t :refer [deftest is]]))

(defn bb [input & args]
  (edn/read-string (apply tu/bb (when (some? input) (str input)) (map str args))))

(deftest classpath-test
  (is (= :my-script/bb
         (bb nil "--classpath" "test-resources/babashka/src_for_classpath_test"
             "(require '[my-script :as ms]) (ms/foo)")))
  (is (= "hello from foo\n"
         (tu/bb nil "--classpath" "test-resources/babashka/src_for_classpath_test/foo.jar"
                "(require '[foo :as f]) (f/foo)")))
  (is (thrown-with-msg? Exception #"not require"
         (tu/bb nil
                "(require '[foo :as f])"))))

(deftest classpath-env-test
  ;; for this test you have to set `BABASHKA_CLASSPATH` to test-resources/babashka/src_for_classpath_test/env
  ;; and `BABASHKA_PRELOADS` to "(require '[env-ns])"
  (when (System/getenv "BABASHKA_CLASSPATH_TEST")
    (println (System/getenv "BABASHKA_CLASSPATH"))
    (is (= "env!" (bb nil "(env-ns/foo)")))))

(deftest main-test
  (is (= "(\"1\" \"2\" \"3\" \"4\")\n"
         (tu/bb nil "--classpath" "test-resources/babashka/src_for_classpath_test" "-m" "my.main" "1" "2" "3" "4"))))

(deftest uberscript-test
  (let [tmp-file (java.io.File/createTempFile "uberscript" ".clj")]
    (.deleteOnExit tmp-file)
    (is (empty? (tu/bb nil "--classpath" "test-resources/babashka/src_for_classpath_test" "-m" "my.main" "--uberscript" (.getPath tmp-file))))
    (is (= "(\"1\" \"2\" \"3\" \"4\")\n"
           (tu/bb nil "--file" (.getPath tmp-file) "1" "2" "3" "4")))))

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
    (bb nil "--classpath" "logo" "-e" (format "(io/copy (io/input-stream (io/resource \"icon.png\")) (io/file \"%s\"))" (.getPath tmp-file)))
    (is (= (.length (io/file "logo" "icon.png"))
           (.length tmp-file)))))
