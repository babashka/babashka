# babashka

[![CircleCI](https://circleci.com/gh/borkdude/babashka/tree/master.svg?style=shield)](https://circleci.com/gh/borkdude/babashka/tree/master)
[![Clojars Project](https://img.shields.io/clojars/v/borkdude/babashka.svg)](https://clojars.org/borkdude/babashka)
[![cljdoc badge](https://cljdoc.org/badge/borkdude/babashka)](https://cljdoc.org/d/borkdude/babashka/CURRENT)

A tiny, pure and fast implementation of Clojure in Clojure for shell scripting.

Properties:

- pure (no side effects)
- fast startup time
- interprets only one form
- reads from stdin and writes to stdout

## Rationale

If you're a bash expert, you probably don't need this. But for those of us who
can use a tiny sprinkle of Clojure on their shell scripts, it may be useful. If
most of your script evolves into Clojure, you might want to turn to:

- [planck](https://planck-repl.org/)
- [joker](https://github.com/candid82/joker)
- [closh](https://github.com/dundalek/closh)
- [lumo](https://github.com/anmonteiro/lumo)

## Status

Experimental. Breaking changes are expected to happen at this phase.

## Installation

Linux and macOS binaries are provided via brew.

Install:

    brew install borkdude/brew/babashka

Upgrade:

    brew upgrade babashka

You may also download a binary from [Github](https://github.com/borkdude/babashka/releases).

## Usage

``` shellsession
... | bb [-i] [-o] '<Clojure form>'
```

There is one special variable, `*in*`, which is the input read from stdin. The
input is read as EDN by default. If the `-i` flag is provided, then the input is
read as a string which is then split on newlines. The output is printed as EDN
by default, unless the `-o` flag is provided, then the output is turned into
shell-scripting friendly output. To combine `-i` and `-o` you can use `-io`.

The current version can be printed with `bb --version`.

Currently only the macros `if`, `when`, `and`, `or`, `->` and `->>` are
supported.

Examples:

``` shellsession
$ ls | bb -i '*in*'
["LICENSE" "README.md" "bb" "doc" "pom.xml" "project.clj" "reflection.json" "resources" "script" "src" "target" "test"]

$ ls | bb -i '(count *in*)'
12

$ bb '(vec (dedupe *in*))' <<< '[1 1 1 1 2]'
[1 2]

$ bb '(filterv :foo *in*)' <<< '[{:foo 1} {:bar 2}]'
[{:foo 1}]
```

Anonymous functions literals are allowed with currently up to three positional
arguments.

``` shellsession
$ bb '(#(+ %1 %2 %3) 1 2 *in*)' <<< 3
6
```

``` shellsession
$ ls | bb -i '(filterv #(re-find #"reflection" %) *in*)'
["reflection.json"]
```

Shuffle the lines of a file:

``` shellsession
$ cat /tmp/test.txt
1 Hello
2 Clojure
3 Babashka
4 Goodbye

$ < /tmp/test.txt bb -io '(shuffle *in*)'
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

$ < /tmp/test.txt bb -i '(map-indexed #(vector %1 %2) *in*))' | \
bb '(keep #f(when (re-find #"(?i)clojure" (second %)) (first %)) *in*)'
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

## Gallery

Here's a gallery of more useful examples. Do you have a useful example? PR
welcome!

### Fetch latest Github release tag

For converting JSON to EDN, see [jet](https://github.com/borkdude/jet).

``` shellsession
$ curl -s https://api.github.com/repos/borkdude/babashka/tags \
| jet --from json --keywordize --to edn \
| bb '(-> *in* first :name (subs 1))'
"0.0.4"
```

### Get latest OS-specific download url from Github

``` shellsession
$ curl -s https://api.github.com/repos/borkdude/babashka/releases \
| jet --from json --keywordize \
| bb '(-> *in* first :assets)' \
| bb '(some #(re-find #".*linux.*" (:browser_download_url %)) *in*)'
"https://github.com/borkdude/babashka/releases/download/v0.0.4/babashka-0.0.4-linux-amd64.zip"
```

## License

Copyright © 2019 Michiel Borkent

Distributed under the EPL License, same as Clojure. See LICENSE.
