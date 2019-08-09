# babashka

[![CircleCI](https://circleci.com/gh/borkdude/babashka/tree/master.svg?style=shield)](https://circleci.com/gh/borkdude/babashka/tree/master)
[![Clojars Project](https://img.shields.io/clojars/v/borkdude/babashka.svg)](https://clojars.org/borkdude/babashka)
[![cljdoc badge](https://cljdoc.org/badge/borkdude/babashka)](https://cljdoc.org/d/borkdude/babashka/CURRENT)

A pure, fast and limited version of Clojure in Clojure for shell scripting.

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

## Usage

The first argument to `bb` is the form to be executed. There is one special
variable, `*in*`.

If the first argument is `--version`, then `bb` will print the version and exit.

Examples:

``` shellsession
$ echo 1 | bb '(inc *in*)'
2

$ echo '[1 1 1 1 2]' | bb '(vec (dedupe *in*))'
[1 2]

$ echo '[1 1 1 1 2]' | bb '(inc (first *in*))'
2

$ echo '[{:foo 1} {:bar 2}]' | bb '(filter :foo *in*)'
({:foo 1})

$ echo '"babashka"' | bb '(re-find (re-pattern "b.b.*") *in*)'
"babashka"
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
