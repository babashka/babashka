(ns babashka.fs-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def cwd (fs/real-path "fs"))

(defn temp-dir []
  (-> (fs/create-temp-dir)
      (fs/delete-on-exit)))

(deftest glob-test
  (is (pos? (count (fs/glob cwd "**.{clj,cljc}")))))

(deftest file-name-test
  (is (= "fs" (fs/file-name cwd)))
  (is (= "fs" (fs/file-name (fs/file cwd))))
  (is (= "fs" (fs/file-name (fs/path cwd)))))

(deftest path-test
  (let [p (fs/path "foo" "bar" (io/file "baz"))]
    (is (instance? java.nio.file.Path p))
    (is (= "foo/bar/baz" (str p)))))

(deftest file-test
  (let [f (fs/file "foo" "bar" (fs/path "baz"))]
    (is (instance? java.io.File f))
    (is (= "foo/bar/baz" (str f)))))

(deftest copy-test
  (let [tmp-dir (temp-dir)]
    (fs/copy-tree "fs" tmp-dir)
    (let [cur-dir-count (count (fs/glob "fs" "**" #{:hidden}))
          tmp-dir-count (count (fs/glob tmp-dir "**" #{:hidden}))]
      (is (pos? cur-dir-count))
      (is (= cur-dir-count tmp-dir-count)))))

(deftest components-test
  (let [paths (map str (fs/components cwd))]
    (is (= "fs" (last paths)))
    (is (> (count paths) 1))))

(deftest list-dir-test
  (let [paths (map str (fs/list-dir (fs/real-path ".")))]
    (is (> (count paths) 1)))
  (let [paths (map str (fs/list-dir (fs/real-path ".") (fn accept [x] (fs/directory? x))))]
    (is (> (count paths) 1)))
  (let [paths (map str (fs/list-dir (fs/real-path ".") (fn accept [_] false)))]
    (is (zero? (count paths))))
  (let [paths (map str (fs/list-dir (fs/real-path ".") "*.clj"))]
    (is (pos? (count paths)))))

(deftest delete-tree-test
  (let [tmp-dir1 (temp-dir)
        nested-dir (fs/file tmp-dir1 "foo" "bar" "baz")
        _ (fs/create-dirs nested-dir)]
    (is (fs/exists? nested-dir))
    (fs/delete-tree nested-dir)
    (is (not (fs/exists? nested-dir)))))

(deftest move-test
  (let [tmp-dir1 (fs/create-temp-dir)
        f (fs/file tmp-dir1 "foo.txt")
        _ (spit f "foo")
        f2 (fs/file tmp-dir1 "bar.txt")]
    (fs/move f f2)
    (is (not (fs/exists? f)))
    (is (fs/exists? f2))
    (is (= "foo" (str/trim (slurp f2))))))
