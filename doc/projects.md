# Projects

The following libraries and projects are known to work with babashka.

- [Projects](#projects)
  - [Libraries](#libraries)
    - [tools.namespace](#toolsnamespace)
    - [test-runner](#test-runner)
    - [spec.alpha](#specalpha)
    - [clj-http-lite](#clj-http-lite)
    - [spartan.spec](#spartanspec)
    - [missing.test.assertions](#missingtestassertions)
    - [medley](#medley)
    - [limit-break](#limit-break)
    - [clojure-csv](#clojure-csv)
    - [regal](#regal)
    - [cprop](#cprop)
    - [comb](#comb)
    - [nubank/docopt](#nubankdocopt)
    - [arrangement](#arrangement)
    - [clojure.math.combinatorics](#clojuremathcombinatorics)
    - [testdoc](#testdoc)
    - [doric](#doric)
    - [clojure.data.zip](#clojuredatazip)
    - [clj-psql](#clj-psql)
    - [camel-snake-kebab](#camel-snake-kebab)
    - [aero](#aero)
    - [clojure.data.generators](#clojuredatagenerators)
    - [honeysql](#honeysql)
    - [bond](#bond)
    - [portal](#portal)
    - [version-clj](#version-clj)
    - [matchete](#matchete)
    - [progrock](#progrock)
    - [clj-commons/fs](#clj-commonsfs)
    - [cljc.java-time](#cljcjava-time)
    - [environ](#environ)
    - [gaka](#gaka)
    - [failjure](#failjure)
    - [pretty](#pretty)
    - [clojure-term-colors](#clojure-term-colors)
    - [binf](#binf)
    - [rewrite-edn](#rewrite-edn)
    - [expound](#expound)
    - [omniconf](#omniconf)
    - [slingshot](#slingshot)
    - [hasch](#hasch)
    - [crispin](#crispin)
    - [ffclj](#ffclj)
    - [multigrep](#multigrep)
    - [java-http-clj](#java-http-clj)
    - [component](#component)
    - [minimallist](#minimallist)
    - [ruuter](#ruuter)
    - [clj-commons.digest](#clj-commonsdigest)
    - [contajners](#contajners)
    - [dependency](#dependency)
    - [specmonstah](#specmonstah)
    - [markdown-clj](#markdown-clj)
    - [algo.monads](#algomonads)
    - [datalog-parser](#datalog-parser)
    - [at-at](#at-at)
    - [aysylu/loom](#aysyluloom)
    - [Clarktown](#clarktown)
    - [Malli](#malli)
    - [Meander](#meander)
    - [Schema](#schema)
    - [Sluj](#sluj)
  - [Pods](#pods)
  - [Projects](#projects-1)
    - [babashka-test-action](#babashka-test-action)
    - [deps.clj](#depsclj)
    - [4bb](#4bb)
    - [babashka lambda layer](#babashka-lambda-layer)
    - [Release on push Github action](#release-on-push-github-action)
    - [justone/bb-scripts](#justonebb-scripts)
    - [nativity](#nativity)
    - [cldwalker/bb-clis](#cldwalkerbb-clis)
    - [krell template](#krell-template)
    - [wee-httpd](#wee-httpd)
    - [covid19-babashka](#covid19-babashka)
    - [bb-spotify](#bb-spotify)
    - [lambdaisland/open-source](#lambdaislandopen-source)
    - [dharrigan/spotifyd-notification](#dharriganspotifyd-notification)
    - [nextjournal/ssh-github-auth](#nextjournalssh-github-auth)
    - [turtlequeue/setup-babashka](#turtlequeuesetup-babashka)
    - [interdep](#interdep)
    - [sha-words](#sha-words)
    - [adam-james-v/scripts](#adam-james-vscripts)
    - [oidc-client](#oidc-client)
    - [jirazzz](#jirazzz)
    - [Babashka + scittle guestbook](#babashka--scittle-guestbook)
    - [bb htmx todo app](#bb-htmx-todo-app)
    - [bb aws lambda runtime](#bb-aws-lambda-runtime)
    - [bb-github-app](#bb-github-app)

Also keep an eye on the [news](news.md) page for new projects, gists and other
developments around babashka.

## Libraries

For a full list of libraries, see [libraries.csv](./libraries.csv). To add a
library, see [these instructions](./dev.md#tests-for-libraries).

### [tools.namespace](https://github.com/babashka/tools.namespace)

A fork of `tools.namespace`. This is used by other libraries and enables them to
be supported by babashka.

### [test-runner](https://github.com/cognitect-labs/test-runner)

This library works with the
[tools.namespace](https://github.com/babashka/tools.namespace) fork. See its
readme for an example task for running tests.

### [spec.alpha](https://github.com/babashka/spec.alpha)

A fork of `clojure.spec.alpha` that includes support for generation and
instrumentation! Its readme also contains instructions on how to use
`clojure.core.specs.alpha`.

<!-- ### [tools.bbuild](https://github.com/babashka/tools.bbuild) -->

<!-- A fork of `tools.build`. -->

### [clj-http-lite](https://github.com/clj-commons/clj-http-lite)

Example:

``` shell
$ export BABASHKA_CLASSPATH="$(clojure -Sdeps '{:deps {org.clj-commons/clj-http-lite {:mvn/version "0.4.392"}}}' -Spath)"

$ bb "(require '[clj-http.lite.client :as client]) (:status (client/get \"https://www.clojure.org\"))"
200
```

### [spartan.spec](https://github.com/borkdude/spartan.spec/)

An babashka-compatible implementation of `clojure.spec.alpha`. See
[spec.alpha](#specalpha) for a more complete implementation.

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

Example:

``` shell
$ export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {medley/medley {:mvn/version "1.3.0"}}}')

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

Example:

``` shell
$ export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {lambdaisland/regal {:mvn/version "0.0.143"}}}')

$ bb -e "(require '[lambdaisland.regal :as regal]) (regal/regex [:* \"ab\"])"
#"(?:\Qab\E)*"
```

### [cprop](https://github.com/tolitius/cprop/)

A clojure configuration library. Latest test version: `"0.1.16"`.

### [comb](https://github.com/weavejester/comb)

Simple templating system for Clojure. Latest tested version: `"0.1.1"`.

``` clojure
(require '[babashka.deps :as deps])

(deps/add-deps '{:deps {comb/comb {:mvn/version "0.1.1"}}})

(require '[comb.template :as template])

(template/eval "<% (dotimes [x 3] %>foo<% ) %>") ;;=> "foofoofoo"
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

### [camel-snake-kebab](https://github.com/clj-commons/camel-snake-kebab)

A library for word case conversions.

``` clojure
(require '[babashka.deps :as deps])

(deps/add-deps '{:deps {camel-snake-kebab/camel-snake-kebab {:mvn/version "0.4.2"}}})

(require '[camel-snake-kebab.core :as csk])

(csk/->camelCase 'flux-capacitor) ;;=> 'fluxCapacitor
```

### [aero](https://github.com/juxt/aero/)

A small library for explicit, intentful configuration.

### [clojure.data.generators](https://github.com/clojure/data.generators)

Random data generators

### [honeysql](https://github.com/seancorfield/honeysql)

Turn Clojure data structures into SQL

### [bond](https://github.com/circleci/bond)

Spying and stubbing library, primarily intended for tests.

### [portal](https://github.com/djblue/portal/)

A clojure tool to navigate through your data. This example will launch a browser to view your `deps.edn`:

``` clojure
$ cat deps.edn | bb -e "(babashka.deps/add-deps '{:deps {djblue/portal {:mvn/version \"0.9.0\"}}})" \
                    -e "(require 'portal.main)" \
                    -e '(portal.main/-main "edn")'
```

Also see [examples](https://github.com/babashka/babashka/tree/master/examples#portal).

### [version-clj](https://github.com/xsc/version-clj)

Analysis and comparison of artifact version numbers.

``` clojure
> export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {version-clj/version-clj {:mvn/version "0.1.2"}}}')
> bb --repl
...
user=> (require '[version-clj.core :as ver])
nil
user=> (ver/version->seq "1.0.0-SNAPSHOT")
[(1 0 0) ["snapshot"]]
user=> (ver/version-compare "1.2.3" "1.0.0")
1
user=> (ver/version-compare "1.0.0-SNAPSHOT" "1.0.0")
-1
user=> (ver/version-compare "1.0" "1.0.0")
0
```

### [matchete](https://github.com/xapix-io/matchete.git)

Pattern matching library:

``` clojure
$ rlwrap bb -cp "$(clojure -Spath -Sdeps '{:deps {io.xapix/matchete {:mvn/version "1.2.0"}}}')"
user=> (require '[matchete.core :as mc])
nil
user=> (mc/matches '{?k 1} {:x 1 :y 1})"
({?k :y} {?k :x})
```

### [progrock](https://github.com/weavejester/progrock)

A functional Clojure progress bar for the command line.

Tested version: 0.1.2.

### [clj-commons/fs](https://github.com/clj-commons/fs)

File system utilities for Clojure.

``` clojure
(require '[babashka.deps :as deps])

(deps/add-deps '{:deps {clj-commons/fs {:mvn/version "1.5.2"}}})

(require '[me.raynes.fs :as fs])

(fs/link? "/tmp") ;; true
```

### [cljc.java-time](https://github.com/henryw374/cljc.java-time)

``` clojure
(require '[babashka.deps :as deps])

(deps/add-deps '{:deps {cljc.java-time/cljc.java-time {:mvn/version "0.1.12"}}})

(require  '[cljc.java-time.local-date :as ld])

(def a-date (ld/parse "2019-01-01"))

(ld/plus-days a-date 99)
```

### [environ](https://github.com/weavejester/environ)

Library for managing environment variables in Clojure.

``` clojure
(require '[babashka.deps :as deps])

(babashka.deps/add-deps '{:deps {environ/environ {:mvn/version "1.2.0"}}})

(require '[environ.core :refer [env]])

(prn (:path env))
```

### [gaka](https://github.com/cdaddr/gaka)

``` clojure
(ns script
  (:require [babashka.deps :as deps]))

(deps/add-deps '{:deps {gaka/gaka {:mvn/version "0.3.0"}}})

(require '[gaka.core :as gaka])

(def rules [:div#foo
            :margin "0px"
            [:span.bar
             :color "black"
             :font-weight "bold"
             [:a:hover
              :text-decoration "none"]]])

(println (gaka/css rules))
```

Output:

``` css
div#foo {
  margin: 0px;}

  div#foo span.bar {
    color: black;
    font-weight: bold;}

    div#foo span.bar a:hover {
      text-decoration: none;}
```

### [failjure](https://github.com/adambard/failjure)

Working with failed computations in Clojure.

``` clojure
(require '[babashka.deps :as deps])

(deps/add-deps '{:deps {failjure/failjure {:mvn/version "2.1.1"}}})

(require '[failjure.core :as f])

(f/fail "foo")
```

### [pretty](https://github.com/AvisoNovate/pretty)

The `io.aviso.ansi` namespace provides ANSI font and background color support.

``` clojure
(require '[babashka.deps :as deps])

(deps/add-deps
 '{:deps {io.aviso/pretty {:mvn/version "0.1.36"}}})

(require '[io.aviso.ansi :as ansi])

(println
 (str "The following text will be "
      ansi/bold-red-font "bold and red "
      ansi/reset-font "but this text will not."))
```

### [clojure-term-colors](https://github.com/trhura/clojure-term-colors)

Clojure ASCII color formatting for terminal output.

``` clojure
(require '[babashka.deps :as deps])

(deps/add-deps
 '{:deps {clojure-term-colors/clojure-term-colors {:mvn/version "0.1.0"}}})

(require '[clojure.term.colors :as c])

(println
 (c/yellow "Yellow")
 (c/red "Red")
 "No color")
```

### [binf](https://github.com/helins/binf.cljc)

Handling binary formats in all shapes and forms.

### [rewrite-edn](https://github.com/borkdude/rewrite-edn)

Rewrite EDN with preservation of whitespace, based on rewrite-clj.

Example:

``` clojure
#!/usr/bin/env bb

(require '[babashka.deps :as deps])
(deps/add-deps '{:deps {borkdude/rewrite-edn {:mvn/version "0.0.2"}}})
(require '[borkdude.rewrite-edn :as r])

(def edn-string (slurp "deps.edn"))
(def nodes (r/parse-string edn-string))

(println (str (r/assoc-in nodes [:deps 'my-other-dep] {:mvn/version "0.1.2"})))
```

### [expound](https://github.com/bhb/expound)

Formats `spartan.spec` error messages in a way that is optimized for humans to read.

Example:

``` clojure
#!/usr/bin/env bb

(ns expound
  (:require [babashka.deps :as deps]))

(deps/add-deps
 '{:deps {borkdude/spartan.spec {:git/url "https://github.com/borkdude/spartan.spec"
                                 :sha "bf4ace4a857c29cbcbb934f6a4035cfabe173ff1"}
          expound/expound {:mvn/version "0.8.9"}}})

;; Loading spartan.spec will create a namespace clojure.spec.alpha for compatibility:
(require 'spartan.spec)
(alias 's 'clojure.spec.alpha)

;; Expound expects some vars to be there, like `fdef`. Spartan prints warnings that these are used, but doesn't implement them yet.
(require '[expound.alpha :as expound])

(s/def ::a (s/cat :i int? :j string?))

(expound/expound ::a [1 2])
```

### [omniconf](https://github.com/grammarly/omniconf)

script.clj:
``` clojure
#!/usr/bin/env bb

(ns script
  (:require [babashka.deps :as deps]))

(deps/add-deps
 '{:deps {com.grammarly/omniconf {:mvn/version "0.4.3"}}})

(require '[omniconf.core :as cfg])
(cfg/define {:foo {}})
(cfg/populate-from-env)
(cfg/get :foo)
```

``` text
FOO=1 script.clj
Populating Omniconf from env: 1 value(s)
"1"
```

### [slingshot](https://github.com/scgilardi/slingshot)

Enhanced try and throw for Clojure leveraging Clojure's capabilities.

``` clojure
$ export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {slingshot/slingshot {:mvn/version "0.12.2"}}}')
$ bb -e "(require '[slingshot.slingshot :as s]) (s/try+ (s/throw+ {:type ::foo}) (catch [:type ::foo] [] 1))"
1
```

NOTE: slingshot's tests pass with babashka except one: catching a record types
by name. This is due to a difference in how records are implemented in
babashka. This may be fixed later if this turns out to be really useful.

### [hasch](https://github.com/replikativ/hasch)

Cross-platform (JVM and JS atm.) edn data structure hashing for Clojure.

``` clojure
$ export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {io.replikativ/hasch {:mvn/version "0.3.7"}}}')
$ bb -e "(use 'hasch.core) (edn-hash (range 100))"
(168 252 48 247 180 148 51 182 108 76 20 251 155 187 66 8 124 123 103 28 250 151 26 139 10 216 119 168 101 123 130 225 66 168 48 63 53 99 25 117 173 29 198 229 101 196 162 30 23 145 7 166 232 193 57 239 226 238 240 41 254 78 135 122)
```

NOTE: hasch's tests pass with babashka except the test around hashing
records. This is due to a difference in how records are implemented in
babashka. This may be fixed later if this turns out to be really useful.

### [crispin](https://github.com/dunaj-project/crispin)

Populate a configuration map from multiple sources (environment variables,
system variables, config files, etc.)

Example:

script.clj
``` clojure
#!/usr/bin/env bb

(ns script
  (:require [babashka.deps :as deps]))

(deps/add-deps
 '{:deps {crispin/crispin {:mvn/version "0.3.8"}}})

(require '[crispin.core :as crispin])
(def app-cfg (crispin/cfg))
(app-cfg :foo)
```

``` text
FOO=1 script.clj
"1"
```

### [ffclj](https://github.com/luissantos/ffclj)

A wrapper around executing `ffmpeg` and `ffprobe`. Supports progress reporting via core.async channels.

### [multigrep](https://github.com/clj-commons/multigrep)

Regex-based file grepping and/or text substitution.

Example:
- find the words that are exactly four letters long in some strings:
```clj
(ns multigrep-demo
  (:require [babashka.deps :as deps]
            [clojure.pprint :refer [pprint]])
  (:import (java.io StringReader)))

(deps/add-deps '{:deps {clj-commons/multigrep {:mvn/version "0.5.0"}}})

(require '[multigrep.core :as grep])

; the StringReaders could be anything that clojure.java.io/reader will accept (files, URLs, etc.)
(let [sentence1 (StringReader. "the quick brown fox jumps over the lazy dog")
      sentence2 (StringReader. "Lorem ipsum dolor sit amet")]
  (pprint (grep/grep #"\b[a-z]{4}\b" [sentence1 sentence2])))
```

outputs:
```
({:file
  #object[java.io.StringReader...],
  :line "the quick brown fox jumps over the lazy dog",
  :line-number 1,
  :regex #"\b[a-z]{4}\b",
  :re-seq ("over" "lazy")}
 {:file
  #object[java.io.StringReader...],
  :line "Lorem ipsum dolor sit amet",
  :line-number 1,
  :regex #"\b[a-z]{4}\b",
  :re-seq ("amet")})
```

### [java-http-clj](https://github.com/schmee/java-http-clj)

Http client based on `java.net.http`.

### [component](https://github.com/stuartsierra/component)

A tiny Clojure framework for managing the lifecycle and dependencies of software components which have runtime state.

### [minimallist](https://github.com/green-coder/minimallist)

A minimalist data-driven data model library, inspired by Clojure Spec and Malli.

Example partially borrowed from [minimallist's cljdoc](https://cljdoc.org/d/minimallist/minimallist/CURRENT/doc/usage-in-babashka)

```clj
(require '[babashka.deps :refer [add-deps]])


(add-deps '{:deps {minimallist/minimallist {:git/url "https://github.com/green-coder/minimallist"
                                            :sha     "b373bb18b8868526243735c760bdc67a88dd1e9a"}}})

(require '[minimallist.core :as m])
(require '[minimallist.helper :as h])

(def contact (h/map [:name  (h/fn string?)]
                    [:phone (h/fn string?)]))
(m/valid? contact {:name "Lucy" :phone "5551212"})   ;=> true
(m/valid? contact {:name "Lucy" :phone 5551212}) ;=> false

(m/describe contact {:name "Lucy" :phone "5551212"})   ;=> {:valid? true, :entries {...}}
(m/describe contact {:name "Lucy" :phone 5551212}) ;=> {:valid? false, :entries {... :phone {:valid? false...}}}

;; Does not work for now.
;(require '[clojure.test.check.generators :as tcg])
;(require '[minimallist.generator :as mg])
;(tcg/sample (mg/gen (h/fn int?)))
```

### [ruuter](https://github.com/askonomm/ruuter)

A zero-dependency router where each route is a map. Works with the httpkit server built into babashka.

### [clj-commons.digest](https://github.com/clj-commons/digest)

A message digest library, providing functions for MD5, SHA-1, SHA-256, etc.

### [contajners](https://github.com/lispyclouds/contajners)

An idiomatic, data-driven, REPL friendly clojure client for OCI container engines.

Example:

``` clojure
#!/usr/bin/env bb

(require '[babashka.deps :as deps])

(deps/add-deps '{:deps {org.clojars.lispyclouds/contajners {:mvn/version "0.0.6"}}})

(require '[contajners.core :as c])

(def images-docker (c/client {:engine   :docker
                              :category :images
                              :version  "v1.41"
                              :conn     {:uri "unix:///var/run/docker.sock"}}))

; Pull an image
(c/invoke images-docker {:op     :ImageCreate
                         :params {:fromImage "busybox:musl"}})

; list all images
(c/invoke images-docker {:op :ImageList})
```

### [dependency](https://github.com/stuartsierra/dependency)

Represent dependency graphs as a directed acylic graph.

### [specmonstah](https://github.com/reifyhealth/specmonstah)

Write concise, maintainable test fixtures with clojure.spec.alpha.

### [markdown-clj](https://github.com/yogthos/markdown-clj)

Markdown parser that translates markdown to html.

### [algo.monads](https://github.com/clojure/algo.monads)

Macros for defining monads, and definition of the most common monads.

### [datalog-parser](https://github.com/lambdaforge/datalog-parser)

Datalog parser that is compliant with datomic, datascript and datahike.

### [at-at](https://github.com/overtone/at-at)

Ahead-of-time function scheduler. Compatible with babashka 0.7.7+.

### [aysylu/loom](https://github.com/aysylu/loom)

Graph library for Clojure. Compatible with babashka 0.7.8+.

### [Clarktown](https://github.com/askonomm/clarktown)

An extensible and modular zero-dependency, pure-Clojure Markdown parser.

### [Malli](https://github.com/metosin/malli#babashka)

Data-Driven Schemas for Clojure/Script

### [Meander](https://github.com/noprompt/meander)

Tools for transparent data transformation

### [Schema](https://github.com/plumatic/schema)

Clojure(Script) library for declarative data description and validation

### [Sluj](https://github.com/rawleyfowler/sluj)

Sluj is a very small library for converting strings of UTF-16 text to slugs. A slug is a piece of text that is URL safe.

## Pods

[Babashka pods](https://github.com/babashka/babashka.pods) are programs that can
be used as Clojure libraries by babashka. See
[pod-registry](https://github.com/babashka/pod-registry) for an overview of available pods.

Pods not available in the pod registry:

- [pod-janet-peg](https://github.com/sogaiu/pod-janet-peg): a pod for
  calling [Janet](https://github.com/janet-lang/janet)'s PEG
  functionality.
- [pod-jaydeesimon-jsoup](https://github.com/jaydeesimon/pod-jaydeesimon-jsoup):
    a pod for parsing HTML using CSS queries backed by Jsoup.
- [pod.xledger.sql-server](https://github.com/xledger/pod_sql_server): pod for interacting with SQL Server.

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

Control your spotify player using babashka.

### [lambdaisland/open-source](https://github.com/lambdaisland/open-source)

[Internal
tooling](https://github.com/babashka/babashka/issues/457#issuecomment-636739415)
used by Lambda Island projects. Noteworthy: a [babashka-compatible hiccup
script](https://github.com/lambdaisland/open-source/blob/2cfde3dfb460e72f047bf94e6f5ec7f519c6d7a0/src/lioss/hiccup.clj).

There's also
[subshell](https://github.com/lambdaisland/open-source/blob/master/src/lioss/subshell.clj)
which is like sh/sh, but it inherits stdin/stdout/stderr, so that the user sees
in real time what the subprocess is doing, and can possibly interact with
it. More like how shelling out in a bash script works.

### [dharrigan/spotifyd-notification](https://github.com/dharrigan/spotifyd-notification)

An example of using babashka to show spotifyd notifications via dunst.

### [nextjournal/ssh-github-auth](https://github.com/nextjournal/ssh-github-auth)

A babashka script which uses github auth to fetch SSH public keys. It can be useful to ensure only a certain team of people can access machines with SSH.

### [turtlequeue/setup-babashka](https://github.com/turtlequeue/setup-babashka)

Github Action to install Babashka in your workflows. Useful to run bb scripts in your CI.

### [interdep](https://github.com/rejoice-cljc/interdep)

Manage interdependent dependencies using Clojure's tools.deps and babashka.

### [sha-words](https://github.com/ordnungswidrig/sha-words)

A clojure program to turn a sha hash into list of nouns in a predictable jar.

### [adam-james-v/scripts](https://github.com/adam-james-v/scripts)

A collection of useful scripts. Mainly written with Clojure/babashka

### [oidc-client](https://gist.github.com/holyjak/ad4e1e9b863f8ed57ef0cb6ac6b30494)

Tired of being forced to use the browser every time you need to refresh an OIDC token to authenticate with a backend service? Finally there is a CLI tool for that - the babashka and Docker powered oidc_client.clj.

Upon first invocation it opens up a browser for the OIDC provider login, thereafter it caches the refresh token and uses it as long as it remains valid.

### [jirazzz](https://github.com/rwstauner/jirazzz)

A babashka JIRA client by Randy Stauner

### [Babashka + scittle guestbook](https://github.com/kloimhardt/babashka-scittle-guestbook)

Luminus guestbook example for Babashka + Scittle.

### [bb htmx todo app](https://github.com/prestancedesign/babashka-htmx-todoapp)

Quick example of a todo list SPA using Babashka and htmx.

### [bb aws lambda runtime](https://github.com/tatut/bb-lambda)

AWS Lambda custom runtime for Babashka scripts.

### [bb-github-app](https://github.com/brandonstubbs/bb-github-app)

An example Babashka script that can authenticate as a Github Application,
this example focuses on the checks api.
