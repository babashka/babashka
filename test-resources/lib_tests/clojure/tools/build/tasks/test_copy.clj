;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build.tasks.test-copy
  (:require
    [clojure.test :refer :all]
    [clojure.java.io :as jio]
    [clojure.string :as str]
    [clojure.tools.build.api :as api]
    [clojure.tools.build.util.file :as file]
    [clojure.tools.build.util.zip :as zip]
    [clojure.tools.build.test-util :refer :all])
  (:import
    [java.io File FileInputStream ByteArrayOutputStream]
    [java.nio.file Files LinkOption FileSystems]
    [java.nio.file.attribute PosixFilePermission]
    [java.util UUID]))

(defn slurp-binary
  [^File f]
  (let [fis (FileInputStream. f)
        os (ByteArrayOutputStream.)
        buffer (byte-array 4096)]
    (zip/copy-stream! fis os buffer)
    (.toByteArray os)))

(deftest test-copy
  (with-test-dir "test-data/p1"
    (let [txt (str (UUID/randomUUID))]
      (api/set-project-root! (.getAbsolutePath *test-dir*))
      (api/copy-dir {:target-dir "target/classes"
                     :src-dirs ["src" "resources"]
                     :replace {"__REPLACE__" txt}})
      (let [source-file (jio/file (project-path "target/classes/foo/bar.clj"))
            contents    (slurp source-file)]
        (is (.exists source-file))
        (is (str/includes? contents txt)))

      ;; binary files in replaced exts should be copied but not replaced
      (let [binary-in (jio/file (project-path "resources/test.png"))
            binary-out (jio/file (project-path "target/classes/test.png"))]
        (is (.exists binary-out))
        (is (= (seq (slurp-binary binary-in)) (seq (slurp-binary binary-out))))))))

(deftest test-replace-retains-perms
  (when (contains? (.supportedFileAttributeViews (FileSystems/getDefault)) "posix")
   (with-test-dir "test-data/p1"
     (api/set-project-root! (.getAbsolutePath *test-dir*))
     (let [start-file (jio/file (project-path "target/x/f"))
           _start (file/ensure-file start-file "abc")
           start-path (.toPath start-file)]
       (Files/setPosixFilePermissions start-path #{PosixFilePermission/OWNER_READ PosixFilePermission/GROUP_READ PosixFilePermission/OWNER_EXECUTE})
       (api/copy-dir {:src-dirs [(project-path "target/x")] :target-dir (project-path "target/y") :replace {"abc" "xyz"}})
       (let [end (jio/file (project-path "target/y/f"))
             end-path (.toPath end)
             perms (Files/getPosixFilePermissions end-path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))]
         (is (= (slurp end) "xyz"))
         (is (contains? perms PosixFilePermission/OWNER_EXECUTE)))))))

(comment
  (run-tests)
  )
