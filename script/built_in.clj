#!/usr/bin/env bb

(ns built-in
  (:require [babashka.fs :as fs]
          [babashka.process :refer [shell]]))

;; copy clojure spec as built-in
(fs/with-temp-dir [tmp-dir {}]
  (let [tmp-dir (fs/file tmp-dir)]
    (shell {:dir tmp-dir} "git clone https://github.com/babashka/spec.alpha")
    (let [spec-dir (fs/file tmp-dir "spec.alpha")]
      (shell {:dir spec-dir} "git reset 951b49b8c173244e66443b8188e3ff928a0a71e7 --hard")
      (fs/copy-tree (fs/file spec-dir "src" "main" "clojure") (fs/file "resources" "src" "babashka")
                    {:replace-existing true}))))


