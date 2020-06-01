# Libraries and projects

The following libraries and projects are known to work with babashka.

Table of contents:

- [Libraries](#libraries)
- [Pods](#pods)
- [Projects](#projects)

## Libraries

### [clj-http-lite](https://github.com/babashka/clj-http-lite)

A fork of a fork of `clj-http-lite`. Example:

``` shell
$ export BABASHKA_CLASSPATH="$(clojure -Sdeps '{:deps {clj-http-lite {:git/url "https://github.com/babashka/clj-http-lite" :sha "f44ebe45446f0f44f2b73761d102af3da6d0a13e"}}}' -Spath)"

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

### [clojure.data.zip](https://github.com/clojure/data.zip)

Utilities for clojure.zip, among other things a more fluent way to work 
with xml. 

Small sample:
``` clojure
$ export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {org.clojure/data.zip {:mvn/version "1.0.0"}}}')

$ cat data_zip_xml.clj
(require '[clojure.data.xml :as xml])
(require '[clojure.zip :as zip])
(require '[clojure.data.zip.xml :refer [text attr attr= xml-> xml1-> text=]])

(def data (str "<root>"
               "  <character type=\"person\" name=\"alice\" />"
               "  <character type=\"animal\" name=\"march hare\" />"
               "</root>"))

(let [xml  (-> data java.io.StringReader. xml/parse zip/xml-zip)]
  (prn :alice-is-a (xml1-> xml :character [(attr= :name "alice")] (attr :type)))
  (prn :animal-is-called (xml1-> xml :character [(attr= :type "animal")] (attr :name))))

$ bb data_zip_xml.clj
:alice-is-a "person"
:animal-is-called "march hare"
```
(see for exaple [this article](https://blog.korny.info/2014/03/08/xml-for-fun-and-profit.html#datazip-for-zipper-awesomeness)
for more on clojure.data.zip).

### [clj-psql](https://github.com/DarinDouglass/clj-psql)

A small Clojure wrapper for interacting with `psql`.

```clojure
user> (psql/query conn "select name, subject from grades where grade = 100")
   => ({:name "Bobby Tables", :subject "Math"}
       {:name "Suzy Butterbean", :subject "Math"})
```

### [babashka-compatible hiccup scripts](https://github.com/lambdaisland/open-source/blob/2cfde3dfb460e72f047bf94e6f5ec7f519c6d7a0/src/lioss/hiccup.clj)

There's also [subshell](https://github.com/lambdaisland/open-source/blob/master/src/lioss/subshell.clj)
which is like sh/sh, but it inherits stdin/stdout/stderr, so that the user sees in real time what the subprocess is doing, and can possibly interact with it. More like how shelling out in a bash script works.

## Pods

[Babashka pods](https://github.com/babashka/babashka.pods) are programs that can
be used as Clojure libraries by babashka.

- [babashka-sql-pods](https://github.com/babashka/babashka-sql-pods): pods for
  interacting with SQL databases
- [bootleg](https://github.com/retrogradeorbit/bootleg): static HTML website
  generation
- [clj-kondo](https://github.com/borkdude/clj-kondo/#babashka-pod): a Clojure
  linter
- [pod-babashka-filewatcher](https://github.com/babashka/pod-babashka-filewatcher): a
  filewatcher pod based on Rust notify
- [pod-janet-peg](https://github.com/sogaiu/pod-janet-peg): a pod for
  calling [Janet](https://github.com/janet-lang/janet)'s PEG
  functionality
- [pod-jaydeesimon-jsoup](https://github.com/jaydeesimon/pod-jaydeesimon-jsoup):
    a pod for parsing HTML using CSS queries backed by Jsoup
- [pod-lispyclouds-docker](https://github.com/lispyclouds/pod-lispyclouds-docker):
  A pod for interacting with docker

## Projects

### [babashka-test-action](https://github.com/marketplace/actions/babashka-test-action)

Github Action to run clojure.test by Babashka.

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

Babashka script for creating React Native (Krell) project

### [wee-httpd](https://github.com/bherrmann7/bb-common/blob/master/wee_httpd.bb)

A wee multi-threaded web server

### [covid19-babashka](https://github.com/agrison/covid19-babashka)

A babashka script to obtain covid-19 related information. 

### [bb-spotify](https://github.com/kolharsam/bb-spotify)

Contol your spotify player using babashka.

### [lambdaisland/open-source](https://github.com/lambdaisland/open-source)

[Internal
tooling](https://github.com/borkdude/babashka/issues/457#issuecomment-636739415)
used by Lambda Island projects.
