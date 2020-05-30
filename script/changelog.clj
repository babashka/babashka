#!/usr/bin/env bb

(ns changelog
  (:require [clojure.string :as str]))

(let [changelog (slurp "CHANGELOG.md")
      replaced (str/replace changelog
                            #"#(\d+)(:? )"
                            (fn [[_ issue after]]
                              (format "[#%s](https://github.com/borkdude/babashka/issues/%s)%s"
                                      issue issue after)))]
  (spit "CHANGELOG.md" replaced))
