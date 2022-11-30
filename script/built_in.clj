#!/usr/bin/env bb

(ns built-in
  (:require [babashka.fs :as fs]
          [babashka.process :refer [shell]]))

;; copy clojure spec as built-in
(fs/with-temp-dir [tmp-dir {}]
  (let [tmp-dir (fs/file tmp-dir)]
    (shell {:dir tmp-dir} "git clone https://github.com/babashka/spec.alpha")
    (let [spec-dir (fs/file tmp-dir "spec.alpha")]
      (shell {:dir spec-dir} "git reset 1d9df099be4fbfd30b9b903642ad376373c16298 --hard")
      (fs/copy-tree (fs/file spec-dir "src" "main" "clojure") (fs/file "resources" "src" "babashka")))))


