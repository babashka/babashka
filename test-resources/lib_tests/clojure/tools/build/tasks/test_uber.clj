;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build.tasks.test-uber
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.test :refer :all]
    [clojure.java.io :as jio]
    [clojure.tools.build.api :as api]
    [clojure.tools.build.tasks.uber :as uber]
    [clojure.tools.build.test-util :refer :all]
    [clojure.tools.build.util.zip :as zip]
    [clojure.tools.build.tasks.test-jar :as test-jar])
  (:import
    [clojure.lang ExceptionInfo]
    [java.io ByteArrayInputStream]))

(defn- string->stream
  [^String s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(deftest string-stream-rt
  (are [s] (= s (#'uber/stream->string (string->stream s)))
    ""
    "abc"))

(deftest test-uber
  (let [uber-path "target/p1-uber.jar"]
    (with-test-dir "test-data/p1"
      (api/set-project-root! (.getAbsolutePath *test-dir*))
      (api/javac {:class-dir "target/classes"
                  :src-dirs ["java"]})
      (api/copy-dir {:target-dir "target/classes"
                     :src-dirs ["src"]})
      (api/uber {:class-dir "target/classes"
                 :basis (api/create-basis nil)
                 :uber-file uber-path
                 :main 'foo.bar})
      (let [uf (jio/file (project-path uber-path))]
        (is (true? (.exists uf)))
        (is (set/subset?
              #{"META-INF/MANIFEST.MF" "foo/" "foo/bar.clj" "foo/Demo2.class" "foo/Demo1.class"}
              (set (map :name (zip/list-zip (project-path uber-path))))))
        (is (str/includes? (test-jar/slurp-manifest uf) "Main-Class: foo.bar"))))))

(deftest test-custom-manifest
  (let [uber-path "target/p1-uber.jar"]
    (with-test-dir "test-data/p1"
      (api/set-project-root! (.getAbsolutePath *test-dir*))
      (api/javac {:class-dir "target/classes"
                  :src-dirs ["java"]})
      (api/copy-dir {:target-dir "target/classes"
                     :src-dirs ["src"]})
      (api/uber {:class-dir "target/classes"
                 :uber-file uber-path
                 :main 'foo.bar
                 :manifest {"Main-Class" "baz" ;; overrides :main
                            'Custom-Thing 100}}) ;; stringify kvs
      (let [uf (jio/file (project-path uber-path))]
        (is (true? (.exists uf)))
        (is (= #{"META-INF/MANIFEST.MF" "foo/" "foo/bar.clj" "foo/Demo2.class" "foo/Demo1.class"}
              (set (map :name (zip/list-zip (project-path uber-path))))))
        (let [manifest-out (test-jar/slurp-manifest uf)]
          (is (str/includes? manifest-out "Main-Class: baz"))
          (is (str/includes? manifest-out "Custom-Thing: 100")))))))

(deftest test-conflicts
  (with-test-dir "test-data/uber-conflict"
    (api/set-project-root! (.getAbsolutePath *test-dir*))

    ;; make "jars"
    (doseq [j ["j1" "j2" "j3"]]
      (let [classes (format "target/%s/classes" j)
            jar-file (format "target/%s.jar" j)]
        (api/copy-dir {:target-dir classes :src-dirs [j]})
        (api/jar {:class-dir classes :jar-file jar-file})))

    ;; uber including j1, j2, j3
    (api/uber {:class-dir "target/classes"
               :basis (api/create-basis {:root nil
                                         :project {:deps {'dummy/j1 {:local/root "target/j1.jar"}
                                                          'dummy/j2 {:local/root "target/j2.jar"}
                                                          'dummy/j3 {:local/root "target/j3.jar"}}}})
               :uber-file "target/conflict.jar"
               :conflict-handlers {"ignore.txt" :ignore
                                   "overwrite.txt" :overwrite
                                   "append.txt" :append}})

    ;; unzip
    (api/unzip {:zip-file "target/conflict.jar" :target-dir "target/unzip"})

    ;; non-conflicting files are combined, conflicting files are reconciled
    (let [fs (map :name (zip/list-zip (project-path "target/conflict.jar")))]
      (is
        (set/subset?
          #{"META-INF/LICENSE.txt" "META-INF/MANIFEST.MF"
            "data_readers.clj"
            "my/j1.txt" "my/j2.txt"
            "ignore.txt" "overwrite.txt" "append.txt"}
          (set fs))))

    ;; data_readers.clj merge
    (is (= '{j1a my.foo/j1a-reader, j1b my.bar/j1b-reader,
             j2a my.foo/j2a-reader, j2b my.bar/j2b-reader}
          (read-string (slurp (project-path "target/unzip/data_readers.clj")))))

    ;; data_readers.cljc merge
    (is (= {'j1a (reader-conditional '(:cljs my.cljs.foo/j1a-reader :clj my.clj.foo/j1a-reader) false)
            'j1b (reader-conditional '(:cljs my.cljs.foo/j1b-reader :clj my.clj.foo/j1b-reader) false)
            'j2a (reader-conditional '(:cljs my.cljs.foo/j2a-reader :clj my.clj.foo/j2a-reader) false)
            'j2b (reader-conditional '(:cljs my.cljs.foo/j2b-reader :clj my.clj.foo/j2b-reader) false)}
         (read-string {:read-cond :preserve :features #{:clj}}
                      (slurp (project-path "target/unzip/data_readers.cljc")))))

    ;; ignore files ignore, so first one wins
    (is (= (slurp (project-path "j1/ignore.txt"))
          (slurp (project-path "target/unzip/ignore.txt"))))

    ;; overwrite files overwrite, so last wins
    (is (= (slurp (project-path "j2/overwrite.txt"))
          (slurp (project-path "target/unzip/overwrite.txt"))))

    ;; append files append
    (is (= (str (slurp (project-path "j1/append.txt")) "\n"
             (slurp (project-path "j2/append.txt")))
          (slurp (project-path "target/unzip/append.txt"))))

    ;; LICENSE files append but no dupes - include j1 and j2, but not j3 (dupe of j1)
    (is (= (str (slurp (project-path "j1/META-INF/LICENSE.txt")) "\n"
             (slurp (project-path "j2/META-INF/LICENSE.txt")))
          (slurp (project-path "target/unzip/META-INF/LICENSE.txt"))))))

(deftest test-conflicts-but-files
  (with-test-dir "test-data/uber-conflict"
    (api/set-project-root! (.getAbsolutePath *test-dir*))

    ;; make dirs
    (doseq [j ["j1" "j2" "j3"]]
      (let [classes (format "target/%s/classes" j)]
        (api/copy-dir {:target-dir classes :src-dirs [j]})
        (spit (project-path (format "target/%s/classes/deps.edn" j)) "{:paths [\".\"]}")))

    ;; uber including j1, j2, j3 as local dirs
    (api/uber {:class-dir "target/classes"
               :basis (api/create-basis {:root nil
                                         :project {:deps {'dummy/j1 {:local/root "target/j1/classes"}
                                                          'dummy/j2 {:local/root "target/j2/classes"}
                                                          'dummy/j3 {:local/root "target/j3/classes"}}}})
               :uber-file "target/conflict.jar"
               :conflict-handlers {"ignore.txt" :ignore
                                   "overwrite.txt" :overwrite
                                   "append.txt" :append}})

    ;; unzip
    (api/unzip {:zip-file "target/conflict.jar" :target-dir "target/unzip"})

    ;; non-conflicting files are combined, conflicting files are reconciled
    (let [fs (map :name (zip/list-zip (project-path "target/conflict.jar")))]
      (is
       (set/subset?
        #{"META-INF/LICENSE.txt" "META-INF/MANIFEST.MF"
          "data_readers.clj"
          "my/j1.txt" "my/j2.txt"
          "ignore.txt" "overwrite.txt" "append.txt"}
        (set fs))))

    ;; data_readers.clj merge
    (is (= '{j1a my.foo/j1a-reader, j1b my.bar/j1b-reader,
             j2a my.foo/j2a-reader, j2b my.bar/j2b-reader}
           (read-string (slurp (project-path "target/unzip/data_readers.clj")))))

    ;; data_readers.cljc merge
    (is (= {'j1a (reader-conditional '(:cljs my.cljs.foo/j1a-reader :clj my.clj.foo/j1a-reader) false)
            'j1b (reader-conditional '(:cljs my.cljs.foo/j1b-reader :clj my.clj.foo/j1b-reader) false)
            'j2a (reader-conditional '(:cljs my.cljs.foo/j2a-reader :clj my.clj.foo/j2a-reader) false)
            'j2b (reader-conditional '(:cljs my.cljs.foo/j2b-reader :clj my.clj.foo/j2b-reader) false)}
           (read-string {:read-cond :preserve :features #{:clj}}
                        (slurp (project-path "target/unzip/data_readers.cljc")))))

    ;; ignore files ignore, so first one wins
    (is (= (slurp (project-path "j1/ignore.txt"))
           (slurp (project-path "target/unzip/ignore.txt"))))

    ;; overwrite files overwrite, so last wins
    (is (= (slurp (project-path "j2/overwrite.txt"))
           (slurp (project-path "target/unzip/overwrite.txt"))))

    ;; append files append
    (is (= (str (slurp (project-path "j1/append.txt")) "\n"
                (slurp (project-path "j2/append.txt")))
           (slurp (project-path "target/unzip/append.txt"))))

    ;; LICENSE files append but no dupes - include j1 and j2, but not j3 (dupe of j1)
    (is (= (str (slurp (project-path "j1/META-INF/LICENSE.txt")) "\n"
                (slurp (project-path "j2/META-INF/LICENSE.txt")))
           (slurp (project-path "target/unzip/META-INF/LICENSE.txt"))))))

(deftest test-case-sensitive-dir-file-collision
  (with-test-dir "test-data/case-sensitive-collision"
    (api/set-project-root! (.getAbsolutePath *test-dir*))

    ;; make "jars"
    (doseq [j ["j1" "j2"]]
      (let [classes (format "target/%s/classes" j)
            jar-file (format "target/%s.jar" j)]
        (api/copy-dir {:target-dir classes :src-dirs [j]})
        (api/jar {:class-dir classes :jar-file jar-file})))

    ;; uber including j1, j2 - should fail with conflict
    (let [basis (api/create-basis {:root nil
                                   :project {:deps {'dummy/j1 {:local/root "target/j1.jar"}
                                                    'dummy/j2 {:local/root "target/j2.jar"}}}})]
      (try
        (api/uber {:class-dir "target/classes", :basis basis, :uber-file "target/collision.jar"})
        (catch ExceptionInfo ex
          (= "Cannot write foo/hi.txt from dummy/j2 as parent dir is a file from another lib. One of them must be excluded."
            (ex-message ex))))

      ;; uber including j1, j2 but excluding one of the conflicts
      (api/uber {:class-dir "target/classes", :basis basis, :uber-file "target/collision.jar"
                 :exclude ["FOO"]})

      ;; after exclusion, only foo/hi.txt
      (let [fs (map :name (zip/list-zip (project-path "target/collision.jar")))]
        (is (= #{"META-INF/MANIFEST.MF" "foo/" "foo/hi.txt"} (set fs)))))))

;; TBUILD-35 - it is rare but possible for a jar to contain a / dir entry
(deftest test-bad-zip-with-root-dir
  (with-test-dir "test-data/bad-zip"
    (api/set-project-root! (.getAbsolutePath *test-dir*))
    (let [basis (api/create-basis {:root nil})]
      (api/uber {:class-dir "target/classes" :basis basis, :uber-file "target/out.jar"})
      (.exists (jio/file (api/resolve-path "target/out.jar"))))))

(comment
  (test-conflicts)
  (test-conflicts-but-files)
  (test-case-sensitive-dir-file-collision)
  (run-tests)
  )
