#!/usr/bin/env bb

(ns changelog
  (:require [clojure.string :as str]))

(let [changelog (slurp "CHANGELOG.md")
      replaced (str/replace changelog
                            #" #(\d+)"
                            (fn [[_ issue after]]
                              (format " [#%s](https://github.com/babashka/babashka/issues/%s)%s"
                                      issue issue (str after))))
      replaced (str/replace replaced
                            #" borkdude/sci#(\d+)"
                            (fn [[_ issue after]]
                              (format " [borkdude/sci#%s](https://github.com/borkdude/sci/issues/%s)%s"
                                      issue issue (str after))))
      replaced (str/replace replaced
                            #" babashka/babashka.nrepl#(\d+)"
                            (fn [[_ issue after]]
                              (format " [babashka/babashka.nrepl#%s](https://github.com/babashka/babashka.nrepl/issues/%s)%s"
                                      issue issue (str after))))
      replaced (str/replace replaced
                            #"@([a-zA-Z0-9-_]+)([, \.)])"
                            (fn [[_ name after]]
                              (format "[@%s](https://github.com/%s)%s"
                                      name name after)))]
  (spit "CHANGELOG.md" replaced))
