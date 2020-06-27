(ns babashka.file-property1
  (:require [clojure.java.io :as io]))

(prn (= *file* (System/getProperty "babashka.file")))

(load-file (.getPath (io/file "test-resources" "babashka" "file_property2.clj")))
