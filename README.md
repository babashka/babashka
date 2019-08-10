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

## Rationale

Most of your script is in bash, but you want a tiny sprinkle of Clojure.

If most of your shell script evolves into Clojure, you might want to turn to:

- [planck](https://planck-repl.org/)
- [joker](https://github.com/candid82/joker)
- [closh](https://github.com/dundalek/closh)
- [lumo](https://github.com/anmonteiro/lumo)

## Status

Experimental. Breaking changes are expected to happen at this phase. Not all
Clojure core functions are supported yet, but can be easily
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

``` shellsession
... | bb [--raw] [--println] '<Clojure form>'
```

There is one special variable, `*in*`, which is the input read from stdin. The
input is read as EDN by default, unless the `--raw` flag is provided. When using
the `--println` flag, the output is printed using `println` instead of `prn`.

The current version can be printed with:

``` shellsession
bb --version
0.0.3
```

Examples:

``` shellsession
$ ls | bb --raw '*in*'
["LICENSE" "README.md" "bb" "doc" "pom.xml" "project.clj" "reflection.json" "resources" "script" "src" "target" "test"]

$ ls | bb --raw '(count *in*)'
11

$ bb '(vec (dedupe *in*))' <<< '[1 1 1 1 2]'
[1 2]

$ bb '(filter :foo *in*)' <<< '[{:foo 1} {:bar 2}]'
({:foo 1})
```

Functions are written using the reader tag `#f`. Currently up to three
arguments are supported.

``` shellsession
$ bb '(#f(+ %1 %2 %3) 1 2 *in*)' <<< 3
6
```

Regexes are written using the reader tag `#r`.

``` shellsession
$ ls | bb --raw '(filterv #f(re-find #r "reflection" %) *in*)'
["reflection.json"]
```

Shuffle the lines of a file:

``` shellsession
$ cat /tmp/test.txt
1 Hello
2 Clojure
3 Babashka
4 Goodbye

$ < /tmp/test.txt bb --raw '(shuffle *in*)' | bb --println '(str/join "\n" *in*)'
3 Babashka
2 Clojure
4 Goodbye
1 Hello
```

Find the line numbers where the word Clojure occurs using a case insensitive regex:

``` shellsession
$ cat /tmp/test.txt
foo
Clojure is nice
bar
when you're nice to clojure

$ < /tmp/test.txt bb --raw '(map-indexed #f[%1 %2] *in*))' | \
bb '(keep #f(when (re-find #r"(?i)clojure" (second %)) (first %)) *in*)'
(1 3)
```

## Test

Test on the JVM:

    script/test

Although this tool doesn't offer any benefit when running on the JVM, it is
convenient for development.

Test the native version:

    BABASHKA_TEST_ENV=native script/test

## Build

You will need leiningen and GraalVM.

    script/compile

## License

Copyright © 2019 Michiel Borkent

Distributed under the EPL License, same as Clojure. See LICENSE.
