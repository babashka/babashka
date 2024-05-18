(ns babashka.uberjar-test
  (:require
   [babashka.fs :as fs]
   [babashka.main :as main]
   [babashka.process :refer [shell]]
   [babashka.test-utils :as tu]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is testing]])
  (:import (java.io File InputStreamReader PushbackReader)
           (java.util.jar JarFile)))

(defn jar-entries [jar]
  (with-open [jar-file (JarFile. jar)]
    (doall (enumeration-seq (.entries jar-file)))))

(defn count-entries [jar]
  (-> jar jar-entries count))

(defn get-entry [^File jar entry-name]
  (-> jar JarFile. (.getEntry entry-name)))

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

(deftest uberjar-with-pods-test
  (testing "jar contains bb.edn w/ only :pods when bb.edn has :pods"
    (let [tmp-file (java.io.File/createTempFile "uber" ".jar")
          path (.getPath tmp-file)]
      (.deleteOnExit tmp-file)
      (let [config {:paths ["test-resources/babashka/uberjar/src"]
                    :deps '{local/deps {:local/root "test-resources/babashka/uberjar"}}
                    :pods (cond-> '{org.babashka/go-sqlite3 {:version "0.1.0"}}
                            (not (fs/windows?)) (assoc 'pod/test-pod {:path "test-resources/pod"}))}]
        (tu/with-config config
          (tu/bb nil "uberjar" path "-m" "my.main-pod")
          (let [bb-edn-entry (get-entry tmp-file "META-INF/bb.edn")
                bb-edn (-> path JarFile. (.getInputStream bb-edn-entry)
                           InputStreamReader. PushbackReader. edn/read)]
            (is (= #{:pods} (-> bb-edn keys set)))
            (is (= (:pods config) (:pods bb-edn))))
          (is (str/includes? (tu/bb nil "--prn" "--jar" path) "3")))))))

(deftest uberjar-as-binary-test
  (when tu/native?
    (let [tmp-file (java.io.File/createTempFile "uber" ".jar")
          path (.getPath tmp-file)
          bin-file (if (fs/windows?) "my-binary.exe" "my-binary")]
      (.deleteOnExit tmp-file)
      (.deleteOnExit (io/file bin-file))
      (tu/bb nil "--classpath" "test-resources/babashka/uberjar/src" "uberjar" path "-m" "my.main-main")
      (shell {:out bin-file} "cat" "./bb" path)
      (.setExecutable (io/file bin-file) true)
      (is (str/includes? (:out (shell {:out :string} (str (io/file "." bin-file)) "1 2 3 4")) "1 2 3 4")))))
