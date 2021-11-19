(ns babashka.impl.fs
  (:require [babashka.fs :as fs]
            [sci.core :as sci]))

(def current-file (fs/file (fs/absolutize (fs/path "src" *file*))))

(def fs-namespace
  (sci/copy-ns 'babashka.fs (sci/create-ns 'babashka.fs)))

