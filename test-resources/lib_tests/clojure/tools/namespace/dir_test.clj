(ns clojure.tools.namespace.dir-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.tools.namespace.test-helpers :as help]
            [clojure.tools.namespace.dir :as dir])
  (:import
   (java.io File)))

;; Only run this test on Java 1.7+, where java.nio.file.Files is available.
(when (try (Class/forName "java.nio.file.Files")
           (catch ClassNotFoundException _ false))
  (deftest t-scan-by-canonical-path
    (let [dir (help/create-temp-dir "t-scan-by-canonical-path")
          main-clj (help/create-source dir 'example.main :clj '[example.one])
          one-cljc (help/create-source dir 'example.one :clj)
          other-dir (help/create-temp-dir "t-scan-by-canonical-path-other")
          link (File. other-dir "link")]
      (java.nio.file.Files/createSymbolicLink (.toPath link) (.toPath dir)
                                              (make-array java.nio.file.attribute.FileAttribute 0))
      (is (= (::dir/files (dir/scan-dirs {} [dir]))
             (::dir/files (dir/scan-dirs {} [link])))))))
