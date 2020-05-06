# Libraries and projects

The following libraries and projects are known to work with babashka.

## Libraries

### [clj-http-lite](https://github.com/borkdude/clj-http-lite)

A fork of a fork of `clj-http-lite`. Example:

``` shell
$ export BABASHKA_CLASSPATH="$(clojure -Sdeps '{:deps {clj-http-lite {:git/url "https://github.com/borkdude/clj-http-lite" :sha "f44ebe45446f0f44f2b73761d102af3da6d0a13e"}}}' -Spath)"

$ bb "(require '[clj-http.lite.client :as client]) (:status (client/get \"https://www.clojure.org\"))"
200
```

### [spartan.spec](https://github.com/borkdude/spartan.spec/)

An babashka-compatible implementation of `clojure.spec.alpha`.

### [missing.test.assertions](https://github.com/borkdude/missing.test.assertions)

This library checks if no assertions have been made in a test:

``` shell
$ export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {borkdude/missing.test.assertions {:git/url "https://github.com/borkdude/missing.test.assertions" :sha "603cb01bee72fb17addacc53c34c85612684ad70"}}}')

$ lein bb "(require '[missing.test.assertions] '[clojure.test :as t]) (t/deftest foo) (t/run-tests)"

Testing user
WARNING: no assertions made in test foo

Ran 1 tests containing 0 assertions.
0 failures, 0 errors.
{:test 1, :pass 0, :fail 0, :error 0, :type :summary}
```

### [medley](https://github.com/weavejester/medley/)

Requires `bb` >= v0.0.71. Latest coordinates checked with with bb:

``` clojure
{:git/url "https://github.com/weavejester/medley" :sha "a4e5fb5383f5c0d83cb2d005181a35b76d8a136d"}
```

Example:

``` shell
$ export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {medley {:git/url "https://github.com/weavejester/medley" :sha "a4e5fb5383f5c0d83cb2d005181a35b76d8a136d"}}}')

$ bb -e "(require '[medley.core :as m]) (m/index-by :id [{:id 1} {:id 2}])"
{1 {:id 1}, 2 {:id 2}}
```

### [limit-break](https://github.com/technomancy/limit-break)

A debug REPL library.

Latest coordinates checked with with bb:

``` clojure
{:git/url "https://github.com/technomancy/limit-break" :sha "050fcfa0ea29fe3340927533a6fa6fffe23bfc2f" :deps/manifest :deps}
```

Example:

``` shell
$ export BABASHKA_CLASSPATH="$(clojure -Sdeps '{:deps {limit-break {:git/url "https://github.com/technomancy/limit-break" :sha "050fcfa0ea29fe3340927533a6fa6fffe23bfc2f" :deps/manifest :deps}}}' -Spath)"

$ bb "(require '[limit.break :as lb]) (let [x 1] (lb/break))"
Babashka v0.0.49 REPL.
Use :repl/quit or :repl/exit to quit the REPL.
Clojure rocks, Bash reaches.

break> x
1
```

### [clojure-csv](https://github.com/davidsantiago/clojure-csv)

A library for reading and writing CSV files. Note that babashka already comes
with `clojure.data.csv`, but in case you need this other library, this is how
you can use it:

``` shell
export BABASHKA_CLASSPATH="$(clojure -Sdeps '{:deps {clojure-csv {:mvn/version "RELEASE"}}}' -Spath)"

./bb -e "
(require '[clojure-csv.core :as csv])
(csv/write-csv (csv/parse-csv \"a,b,c\n1,2,3\"))
"
```

### [regal](https://github.com/lambdaisland/regal)

Requires `bb` >= v0.0.71. Latest coordinates checked with with bb:

``` clojure
{:git/url "https://github.com/lambdaisland/regal" :sha "d4e25e186f7b9705ebb3df6b21c90714d278efb7"}
```

Example:

``` shell
$ export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {regal {:git/url "https://github.com/lambdaisland/regal" :sha "d4e25e186f7b9705ebb3df6b21c90714d278efb7"}}}')

$ bb -e "(require '[lambdaisland.regal :as regal]) (regal/regex [:* \"ab\"])"
#"(?:\Qab\E)*"
```

### [cprop](https://github.com/tolitius/cprop/)

A clojure configuration libary. Latest test version: `"0.1.16"`.

### [comb](https://github.com/weavejester/comb)

Simple templating system for Clojure. Latest tested version: `"0.1.1"`.

``` clojure
$ export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {comb {:mvn/version "0.1.1"}}}')
$ rlwrap bb
...
user=> (require '[comb.template :as template])
user=> (template/eval "<% (dotimes [x 3] %>foo<% ) %>")
"foofoofoo"
user=> (template/eval "Hello <%= name %>" {:name "Alice"})
"Hello Alice"
user=> (def hello (template/fn [name] "Hello <%= name %>"))
user=> (hello "Alice")
"Hello Alice"
```

### [nubank/docopt](https://github.com/nubank/docopt.clj#babashka)

Docopt implementation in Clojure, compatible with babashka.

### [arrangement](https://github.com/greglook/clj-arrangement)

A micro-library which provides a total-ordering comparator for Clojure
values. Tested with version `1.2.0`.

### [clojure.math.combinatorics](https://github.com/clojure/math.combinatorics)

``` clojure
$ bb --classpath "$(clojure -Spath -Sdeps '{:deps {org.clojure/math.combinatorics {:mvn/version "0.1.6"}}}')" \
     -e "(use 'clojure.math.combinatorics) (permutations [:a :b])"
((:a :b) (:b :a))
```

### [testdoc](https://github.com/liquidz/testdoc)

Yet another doctest implementation in Clojure.

``` clojure
$ export BABASHKA_CLASSPATH=$(clojure -Sdeps '{:deps {testdoc {:mvn/version "1.2.0"}}}' -Spath)

$ bb '(ns foo (:use clojure.test testdoc.core))
(defn foo "
  => (foo)
  :foox"
  [] :foo)

(deftest footest
  (is (testdoc (var foo))))

(test-var (var footest))'

FAIL in (footest) (:1)
(= (foo) :foox)
expected: :foox
  actual: :foo
```

### [doric](https://github.com/joegallo/doric)

Library for printing tables.

``` clojure
$ export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {doric {:mvn/version "0.9.0"}}}')
$ bb "(use 'doric.core) (println (table [:a :b :c] [{:a 1 :b 2 :c 3} {:a 4 :b 5 :c 6}]))"
|---+---+---|
| A | B | C |
|---+---+---|
| 1 | 2 | 3 |
| 4 | 5 | 6 |
|---+---+---|
```

## Projects

### [deps.clj](https://github.com/borkdude/deps.clj)

A port of the [clojure](https://github.com/clojure/brew-install/) bash script to
Clojure / babashka.

Also see [deps.clj documentation](../doc/deps.clj.md).

### [4bb](https://github.com/porkostomus/4bb)

4clojure as a babashka script!

### [babashka lambda layer](https://github.com/dainiusjocas/babashka-lambda-layer)

Babashka Lambda runtime packaged as a Lambda layer.

### [Release on push Github action](https://github.com/rymndhng/release-on-push-action)

Github Action to create a git tag + release when pushed to master. Written in
babashka.

### [justone/bb-scripts](https://github.com/justone/bb-scripts)

A collection of scripts developed by [@justone](https://github.com/justone).

### [nativity](https://github.com/MnRA/nativity)

Turn babashka scripts into binaries using GraalVM `native-image`.

### [cldwalker/bb-clis](https://github.com/cldwalker/bb-clis)

A collection of scripts developed by [@cldwalker](https://github.com/cldwalker).

### [krell template](https://github.com/ampersanda/krell-template-runner)

Babashka scfript for creating React Native (Krell) project

### [wee-httpd](https://github.com/bherrmann7/bb-common/blob/master/wee_httpd.bb)

A wee multi-threaded web server
