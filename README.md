# babashka

[![CircleCI](https://circleci.com/gh/borkdude/babashka/tree/master.svg?style=shield)](https://circleci.com/gh/borkdude/babashka/tree/master)
[![Clojars Project](https://img.shields.io/clojars/v/borkdude/babashka.svg)](https://clojars.org/borkdude/babashka)
[![cljdoc badge](https://cljdoc.org/badge/borkdude/babashka)](https://cljdoc.org/d/borkdude/babashka/CURRENT)

A pure, fast and (severely!) limited version of Clojure in Clojure for shell scripting.

Properties:

- pure (no side effects)
- fast startup time
- interprets only one form
- reads from stdin and writes to stdout

## Status

Experimental. Not all Clojure core functions are supported yet, but can be
easily
[added](https://github.com/borkdude/babashka/blob/master/src/babashka/interpreter.clj#L10). PRs
welcome.

## Installation

Linux and macOS binaries are provided via brew.

Install:

    brew install borkdude/brew/babashka

Upgrade:

    brew upgrade babashka

You may also download a binary from [Github](https://github.com/borkdude/babashka/releases).

## Usage

The first argument to `bb` is the form to be executed. There is one special
variable, `*in*` which is EDN that is piped from stdin.

If the first argument is `--version`, then `bb` will print the version and exit.
If the first argument is `--raw`, then `bb` will interpret stdin as string input.

Examples:

``` shellsession
$ ls | bb --raw '*in*'
["LICENSE" "README.md" "bb" "doc" "pom.xml" "project.clj" "reflection.json" "resources" "script" "src" "target" "test"]

$ ls | bb --raw '(count *in*)'
11

$ echo '[1 1 1 1 2]' | bb '(vec (dedupe *in*))'
[1 2]

$ echo '[{:foo 1} {:bar 2}]' | bb '(filter :foo *in*)'
({:foo 1})
```

Functions are written using the reader tag `#f`. Currently up to three
arguments are supported.

``` shellsession
$ echo '3' | bb '(#f (+ %1 %2 %3) 1 2 *in*)'
6
```

Regexes are written using the reader tag `#r`.

``` shellsession
$ ls | bb --raw '(filterv #f (re-find #r "reflection" %) *in*)'
["reflection.json"]
```

## Test

Test the JVM version:

    script/test

Test the native version:

    BABASHKA_TEST_ENV=native script/test

## Build

You will need leiningen and GraalVM.

    script/compile

## License

Copyright © 2019 Michiel Borkent

Distributed under the EPL License, same as Clojure. See LICENSE.
