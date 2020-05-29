<img src="logo/babashka.svg" width="425px">

[![CircleCI](https://circleci.com/gh/borkdude/babashka/tree/master.svg?style=shield)](https://circleci.com/gh/borkdude/babashka/tree/master)
[![project chat](https://img.shields.io/badge/slack-join_chat-brightgreen.svg)](https://app.slack.com/client/T03RZGPFR/CLX41ASCS)
[![Financial Contributors on Open Collective](https://opencollective.com/babashka/all/badge.svg?label=financial+contributors)](https://opencollective.com/babashka) [![Clojars Project](https://img.shields.io/clojars/v/borkdude/babashka.svg)](https://clojars.org/borkdude/babashka)

A Clojure [babushka](https://en.wikipedia.org/wiki/Headscarf) for the grey areas of Bash.

<blockquote class="twitter-tweet" data-lang="en">
    <p lang="en" dir="ltr">Life's too short to remember how to write Bash code. I feel liberated.</p>
    &mdash;
    <a href="https://github.com/laheadle">@laheadle</a> on Clojurians Slack
</blockquote>

## Introduction

The main idea behind babashka is to leverage Clojure in places where you would
be using bash otherwise.

As one user described it:

> I’m quite at home in Bash most of the time, but there’s a substantial grey area of things that are too complicated to be simple in bash, but too simple to be worth writing a clj/s script for. Babashka really seems to hit the sweet spot for those cases.

### Goals

* Low latency Clojure scripting alternative to JVM Clojure.
* Easy installation: grab the self-contained binary and run. No JVM needed.
* Familiarity and portability:
  - Scripts should be compatible with JVM Clojure as much as possible
  - Scripts should be platform-independent as much as possible. Babashka offers
    support for linux, macOS and Windows.
* Allow interop with commonly used classes like `java.io.File` and `System`
* Multi-threading support (`pmap`, `future`, `core.async`)
* Connectivity: talk UDP, TCP, HTTP (and optionally: [JDBC](#JDBC))
* Support for various data formats: JSON, XML, YAML, CSV, bencode
* Batteries included (tools.cli, cheshire, ...)
* Library support via popular tools like the `clojure` CLI

### Non-goals

* Performance<sup>1<sup>
* Provide a mixed Clojure/Bash DSL (see portability).
* Replace existing shells. Babashka is a tool you can use inside existing shells like bash and it is designed to play well with them. It does not aim to replace them.

<sup>1<sup> Babashka uses [sci](https://github.com/borkdude/sci) for
interpreting Clojure. Sci implements a suffiently large subset of
Clojure. Interpreting code is in general not as performant as executing compiled
code. If your script takes more than a few seconds to run, Clojure on the JVM
may be a better fit, since the performance of Clojure on the JVM outweighs its
startup time penalty. Read more about the differences with Clojure
[here](#differences-with-clojure).


### Talk

To get an overview of babashka, you can watch this talk ([slides](https://speakerdeck.com/borkdude/babashka-and-the-small-clojure-interpreter-at-clojured-2020)):

[![Babashka at ClojureD 2020](https://img.youtube.com/vi/Nw8aN-nrdEk/0.jpg)](https://www.youtube.com/watch?v=Nw8aN-nrdEk)

## Quickstart

``` shellsession
$ curl -s https://raw.githubusercontent.com/borkdude/babashka/master/install -o install-babashka
$ chmod +x install-babashka && ./install-babashka
$ ls | bb -i '(filter #(-> % io/file .isDirectory) *input*)'
("doc" "resources" "sci" "script" "src" "target" "test")
bb took 4ms.
```

### Examples

Read the output from a shell command as a lazy seq of strings:

``` shell
$ ls | bb -i '(take 2 *input*)'
("CHANGES.md" "Dockerfile")
```

Read EDN from stdin and write the result to stdout:

``` shell
$ bb '(vec (dedupe *input*))' <<< '[1 1 1 1 2]'
[1 2]
```

Read more about input and output flags
[here](https://github.com/borkdude/babashka/#input-and-output-flags).

Execute a script. E.g. print the current time in California using the
`java.time` API:

File `pst.clj`:
``` clojure
#!/usr/bin/env bb

(def now (java.time.ZonedDateTime/now))
(def LA-timezone (java.time.ZoneId/of "America/Los_Angeles"))
(def LA-time (.withZoneSameInstant now LA-timezone))
(def pattern (java.time.format.DateTimeFormatter/ofPattern "HH:mm"))
(println (.format LA-time pattern))
```

``` shell
$ pst.clj
05:17
```

More examples can be found [here](doc/examples.md).

## Status

Functionality regarding `clojure.core` and `java.lang` can be considered stable
and is unlikely to change. Changes may happen in other parts of babashka,
although we will try our best to prevent them. Always check the release notes or
[CHANGES.md](CHANGES.md) before upgrading.

## Installation

### Brew

Linux and macOS binaries are provided via brew.

Install:

    brew install borkdude/brew/babashka

Upgrade:

    brew upgrade babashka

### Arch (Linux)

`babashka` is [available](https://aur.archlinux.org/packages/babashka-bin/) in the [Arch User Repository](https://aur.archlinux.org). It can be installed using your favorite [AUR](https://aur.archlinux.org) helper such as
[yay](https://github.com/Jguer/yay), [yaourt](https://github.com/archlinuxfr/yaourt), [apacman](https://github.com/oshazard/apacman) and [pacaur](https://github.com/rmarquis/pacaur). Here is an example using `yay`:

    yay -S babashka-bin

### Windows

On Windows you can install using [scoop](https://scoop.sh/) and the
[scoop-clojure](https://github.com/littleli/scoop-clojure) bucket.

### Installer script

Install via the installer script:

``` shellsession
$ bash <(curl -s https://raw.githubusercontent.com/borkdude/babashka/master/install)
```

By default this will install into `/usr/local/bin`. To change this, provide the directory name:

``` shellsession
$ bash <(curl -s https://raw.githubusercontent.com/borkdude/babashka/master/install) /tmp
```

### Download

You may also download a binary from
[Github](https://github.com/borkdude/babashka/releases). For linux there is a
static binary available which can be used on Alpine.

## Docker

Check out the image on [Docker hub](https://hub.docker.com/r/borkdude/babashka/).

## Usage

``` shellsession
Babashka v0.0.90

Options must appear in the order of groups mentioned below.

Help:

  --help, -h or -?    Print this help text.
  --version           Print the current version of babashka.
  --describe          Print an EDN map with information about this version of babashka.

In- and output flags:

  -i                  Bind *input* to a lazy seq of lines from stdin.
  -I                  Bind *input* to a lazy seq of EDN values from stdin.
  -o                  Write lines to stdout.
  -O                  Write EDN values to stdout.
  --stream            Stream over lines or EDN values from stdin. Combined with -i or -I *input* becomes a single value per iteration.

Uberscript:

  --uberscript <file> Collect preloads, -e, -f and -m and all required namespaces from the classpath into a single executable file.

Evaluation:

  -e, --eval <expr>   Evaluate an expression.
  -f, --file <path>   Evaluate a file.
  -cp, --classpath    Classpath to use.
  -m, --main <ns>     Call the -main function from namespace with args.
  --verbose           Print entire stacktrace in case of exception.

REPL:

  --repl              Start REPL. Use rlwrap for history.
  --socket-repl       Start socket REPL. Specify port (e.g. 1666) or host and port separated by colon (e.g. 127.0.0.1:1666).
  --nrepl-server      Start nREPL server. Specify port (e.g. 1667) or host and port separated by colon (e.g. 127.0.0.1:1667).

If neither -e, -f, or --socket-repl are specified, then the first argument that is not parsed as a option is treated as a file if it exists, or as an expression otherwise. Everything after that is bound to *command-line-args*. Use -- to separate script command lin args from bb command line args.
```

The `clojure.core` functions are accessible without a namespace alias.

The following namespaces are required by default and available through the
pre-defined aliases in the `user` namespace. You may use `require` + `:as`
and/or `:refer` on these namespaces. If not all vars are available, they are
enumerated explicitly.

- `clojure.string` aliased as `str`
- `clojure.set` aliased as `set`
- `clojure.edn` aliased as `edn`:
  - `read-string`
- `clojure.java.shell` aliased as `shell`
- `clojure.java.io` aliased as `io`:
  - `as-relative-path`, `as-url`, `copy`, `delete-file`, `file`, `input-stream`,
    `make-parents`, `output-stream`, `reader`, `resource`, `writer`
- `clojure.main`: `repl`
- [`clojure.core.async`](https://clojure.github.io/core.async/) aliased as
  `async`.
- `clojure.stacktrace`
- `clojure.test`
- `clojure.pprint`: `pprint` (currently backed by [fipp](https://github.com/brandonbloom/fipp)'s  `fipp.edn/pprint`)
- [`clojure.tools.cli`](https://github.com/clojure/tools.cli) aliased as `tools.cli`
- [`clojure.data.csv`](https://github.com/clojure/data.csv) aliased as `csv`
- [`clojure.data.xml`](https://github.com/clojure/data.xml) aliased as `xml`
- [`cheshire.core`](https://github.com/dakrone/cheshire) aliased as `json`
- [`cognitect.transit`](https://github.com/cognitect/transit-clj) aliased as `transit`
- [`clj-yaml.core`](https://github.com/clj-commons/clj-yaml) alias as `yaml`
- [`bencode.core`](https://github.com/nrepl/bencode) aliased as `bencode`: `read-bencode`, `write-bencode`
- [`next.jdbc`](https://github.com/seancorfield/next-jdbc) aliased as `jdbc` (available under feature flag)

A selection of java classes are available, see `babashka/impl/classes.clj`.

Babashka supports `import`: `(import clojure.lang.ExceptionInfo)`.

Babashka supports a subset of the `ns` form where you may use `:require` and `:import`:

``` shellsession
(ns foo
  (:require [clojure.string :as str])
  (:import clojure.lang.ExceptionInfo))
```

For the unsupported parts of the ns form, you may use [reader
conditionals](#reader-conditionals) to maintain compatibility with JVM Clojure.

### Running a script

Scripts may be executed from a file using `-f` or `--file`:

``` shellsession
bb -f download_html.clj
```

Using `bb` with a shebang also works:

``` clojure
#!/usr/bin/env bb

(defn get-url [url]
  (println "Fetching url:" url)
  (let [{:keys [:exit :err :out]} (shell/sh "curl" "-sS" url)]
    (if (zero? exit) out
      (do (println "ERROR:" err)
          (System/exit 1)))))

(defn write-html [file html]
  (println "Writing file:" file)
  (spit file html))

(let [[url file] *command-line-args*]
  (when (or (empty? url) (empty? file))
    (println "Usage: <url> <file>")
    (System/exit 1))
  (write-html file (get-url url)))
```

``` shellsession
$ ./download_html.clj
Usage: <url> <file>

$ ./download_html.clj https://www.clojure.org /tmp/clojure.org.html
Fetching url: https://www.clojure.org
Writing file: /tmp/clojure.org.html
```

If `/usr/bin/env` doesn't work for you, you can use the following workaround:

``` shellsession
$ cat script.clj
#!/bin/sh

#_(
   "exec" "bb" "$0" hello "$@"
   )

(prn *command-line-args*)

./script.clj 1 2 3
("hello" "1" "2" "3")
```

### Input and output flags

In one-liners the `*input*` value may come in handy. It contains the input read from stdin as EDN by default. If you want to read in text, use the `-i` flag, which binds `*input*` to a lazy seq of lines of text. If you want to read multiple EDN values, use the `-I` flag. The `-o` option prints the result as lines of text. The `-O` option prints the result as lines of EDN values.

> **Note:** `*input*` is only available in the `user` namespace, on other namespaces use `*in*`.

The following table illustrates the combination of options for commands of the form

    echo "{{Input}}" | bb {{Input flags}} {{Output flags}} "*input*"

| Input          | Input flags | Output flag | `*input*`     | Output   |
|----------------|-------------|-------------|---------------|----------|
| `{:a 1}` <br> `{:a 2}` |             |             | `{:a 1}`      | `{:a 1}` |
| hello <br> bye | `-i`        |             | `("hello" "bye")` |  `("hello" "bye")` |
| hello <br> bye | `-i`        |  `-o`       | `("hello" "bye")` |  hello <br> bye  |
| `{:a 1}` <br> `{:a 2}` | `-I`        |        | `({:a 1} {:a 2})` |  `({:a 1} {:a 2})`   |
| `{:a 1}` <br> `{:a 2}` | `-I` |  `-O`      | `({:a 1} {:a 2})` |  `{:a 1}` <br> `{:a 2}`   |

When combined with the `--stream` option, the expression is executed for each value in the input:

``` clojure
$ echo '{:a 1} {:a 2}' | bb --stream '*input*'
{:a 1}
{:a 2}
```

### Current file path

The var `*file*` contains the full path of the file that is currently being
executed:

``` shellsession
$ cat example.clj
(prn *file*)

$ bb example.clj
"/Users/borkdude/example.clj"
```

### Command-line arguments

Command-line arguments can be retrieved using `*command-line-args*`. If you want
to parse command line arguments, you may use the built-in `clojure.tools.cli`
namespace or use the
[nubank/docopt](https://github.com/borkdude/babashka/blob/master/doc/libraries.md#nubankdocopt)
library.

### Additional namespaces

#### babashka.classpath

Contains the function `add-classpath` which can be used to add to the classpath
dynamically:

``` clojure
(require '[babashka.classpath :refer [add-classpath]]
         '[clojure.java.shell :refer [sh]])
(def medley-dep '{:deps {medley {:git/url "https://github.com/borkdude/medley"
                                 :sha "91adfb5da33f8d23f75f0894da1defe567a625c0"}}})
(def cp (:out (sh "clojure" "-Spath" "-Sdeps" (str medley-dep))))
(add-classpath cp)
(require '[medley.core :as m])
(m/index-by :id [{:id 1} {:id 2}]) ;;=> {1 {:id 1}, 2 {:id 2}}
```

#### babashka.wait

Contains the functions: `wait-for-port` and `wait-for-path`.

Usage of `wait-for-port`:

``` clojure
(wait/wait-for-port "localhost" 8080)
(wait/wait-for-port "localhost" 8080 {:timeout 1000 :pause 1000})
```

Waits for TCP connection to be available on host and port. Options map supports `:timeout` and `:pause`. If `:timeout` is provided and reached, `:default`'s value (if any) is returned. The `:pause` option determines the time waited between retries.

Usage of `wait-for-path`:

``` clojure
(wait/wait-for-path "/tmp/wait-path-test")
(wait/wait-for-path "/tmp/wait-path-test" {:timeout 1000 :pause 1000})
```

Waits for file path to be available. Options map supports `:default`, `:timeout`
and `:pause`. If `:timeout` is provided and reached, `:default`'s value (if any)
is returned. The `:pause` option determines the time waited between retries.

The namespace `babashka.wait` is aliased as `wait` in the `user` namespace.

#### babashka.signal

Contains the function `signal/pipe-signal-received?`. Usage:

``` clojure
(signal/pipe-signal-received?)
```

Returns true if `PIPE` signal was received. Example:

``` shellsession
$ bb '((fn [x] (println x) (when (not (signal/pipe-signal-received?)) (recur (inc x)))) 0)' | head -n2
1
2
```

The namespace `babashka.signal` is aliased as `signal` in the `user` namespace.

#### babashka.curl

The namespace `babashka.curl` is a tiny wrapper around curl. It's aliased as
`curl` in the user namespace.  See
[babashka.curl](https://github.com/borkdude/babashka.curl).

## Style

A note on style. Babashka recommends the following:

### Explicit requires

Use explicit requires with namespace aliases in scripts, unless you're writing
one-liners.

Do this:

``` shell
$ ls | bb -i '(-> *input* first (str/includes? "m"))'
true
```

But not this:

script.clj:
``` clojure
(-> *input* first (str/includes? "m"))
```

Rather do this:

script.clj:
``` clojure
(ns script
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))
  (-> (io/reader *in*) line-seq first (str/includes? "m"))
```

Some reasons for this:

- Linters like clj-kondo work better with code that uses namespace forms, explicit requires, and known Clojure constructs
- Editor tooling works better with namespace forms (sorting requires, etc).
- Writing compatible code gives you the option to run the same script with `clojure`

## [Running a REPL](doc/repl.md)

Babashka offers a REPL, a socket REPL and an nREPL server. Look
[here](doc/repl.md) for more information on how to use and integrate them with
your editor.

## Preloads

The environment variable `BABASHKA_PRELOADS` allows to define code that will be
available in all subsequent usages of babashka.

``` shellsession
BABASHKA_PRELOADS='(defn foo [x] (+ x 2))'
BABASHKA_PRELOADS=$BABASHKA_PRELOADS' (defn bar [x] (* x 2))'
export BABASHKA_PRELOADS
```

Note that you can concatenate multiple expressions. Now you can use these functions in babashka:

``` shellsession
$ bb '(-> (foo *input*) bar)' <<< 1
6
```

You can also preload an entire file using `load-file`:

``` shellsession
export BABASHKA_PRELOADS='(load-file "my_awesome_prelude.clj")'
```

Note that `*input*` is not available in preloads.

## Classpath

Babashka accepts a `--classpath` option that will be used to search for
namespaces and load them:

``` clojure
$ cat src/my/namespace.clj
(ns my.namespace)
(defn -main [& _args]
  (println "Hello from my namespace!"))

$ bb --classpath src --main my.namespace
Hello from my namespace!
```

So if you have a larger script with a classic Clojure project layout like

```shellsession
$ tree -L 3
├── deps.edn
├── README
├── src
│   └── project_namespace
│       ├── main.clj
│       └── utilities.clj
└── test
    └── project_namespace
        ├── test_main.clj
        └── test_utilities.clj
```
Then you can tell Babashka to include both the `src` and `test`
folders in the classpath and start a socket REPL by running:

```shellsession
$ bb --classpath src:test --socket-repl 1666
```

Note that you can use the `clojure` tool to produce classpaths and download dependencies:

``` shellsession
$ cat deps.edn
{:deps
 {my_gist_script
  {:git/url "https://gist.github.com/borkdude/263b150607f3ce03630e114611a4ef42"
   :sha "cfc761d06dfb30bb77166b45d439fe8fe54a31b8"}}
 :aliases {:my-script {:main-opts ["-m" "my-gist-script"]}}}

$ CLASSPATH=$(clojure -Spath)
$ bb --classpath "$CLASSPATH" --main my-gist-script
Hello from gist script!
```

If there is no `--classpath` argument, the `BABASHKA_CLASSPATH` environment
variable will be used:

``` shellsession
$ export BABASHKA_CLASSPATH=$(clojure -Spath)
$ export BABASHKA_PRELOADS="(require '[my-gist-script])"
$ bb "(my-gist-script/-main)"
Hello from gist script!
```

When invoking `bb` with a main function, the expression `(System/getProperty
"babashka.main")` will return the name of the main function.

Also see the
[babashka.classpath](https://github.com/borkdude/babashka/#babashkaclasspath)
namespace which allows dynamically adding to the classpath.

See [deps.clj](doc/deps.clj.md) for a babashka script that replaces the `clojure` bash script.

## Data readers

Data readers can be enabled by setting `*data-readers*` to a hashmap of symbols
to functions or vars:

``` clojure
$ bb "(set! *data-readers* {'t/tag inc}) #t/tag 1"
2
```

To preserve good startup time, babashka does not scan the classpath for
`data_readers.clj` files.

## Uberscript

The `--uberscript` option collects the expressions in
`BABASHKA_PRELOADS`, the command line expression or file, the main entrypoint
and all required namespaces from the classpath into a single file. This can be
convenient for debugging and deployment.

Given the `deps.edn` from above:

``` clojure
$ deps.clj -A:my-script -Scommand "bb -cp {{classpath}} {{main-opts}} --uberscript my-script.clj"

$ cat my-script.clj
(ns my-gist-script)
(defn -main [& args]
  (println "Hello from gist script!"))
(ns user (:require [my-gist-script]))
(apply my-gist-script/-main *command-line-args*)

$ bb my-script.clj
Hello from gist script!
```

## Parsing command line arguments

Babashka ships with `clojure.tools.cli`:

``` clojure
(require '[clojure.tools.cli :refer [parse-opts]])

(def cli-options
  ;; An option with a required argument
  [["-p" "--port PORT" "Port number"
    :default 80
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-h" "--help"]])

(:options (parse-opts *command-line-args* cli-options))
```

``` shellsession
$ bb script.clj
{:port 80}
$ bb script.clj -h
{:port 80, :help true}
```

## Reader conditionals

Babashka supports reader conditionals by taking either the `:bb` or `:clj`
branch, whichever comes first. NOTE: the `:clj` branch behavior was added in
version 0.0.71, before that version the `:clj` branch was ignored.

``` clojure
$ bb "#?(:bb :hello :clj :bye)"
:hello

$ bb "#?(:clj :bye :bb :hello)"
:bye

$ bb "[1 2 #?@(:bb [] :clj [1])]"
[1 2]
```

## Running tests

Babashka bundles `clojure.test`. To make CI scripts fail you can use a simple
runner like this:

``` shell
#!/usr/bin/env bash
bb -cp "src:test:resources" \
   -e "(require '[clojure.test :as t] '[borkdude.deps-test])
       (let [{:keys [:fail :error]} (t/run-tests 'borkdude.deps-test)]
         (System/exit (+ fail error)))"
```

## Spawning and killing a process

Use the `java.lang.ProcessBuilder` class.

Example:

``` clojure
user=> (def ws (-> (ProcessBuilder. ["python" "-m" "SimpleHTTPServer" "1777"]) (.start)))
#'user/ws
user=> (wait/wait-for-port "localhost" 1777)
{:host "localhost", :port 1777, :took 2}
user=> (.destroy ws)
nil
```

Also see this [example](examples/process_builder.clj).

## Async

In addition to `future`, `pmap`, `promise` and friends, you may use the
`clojure.core.async` namespace for asynchronous scripting. The following example
shows how to get first available value from two different processes:

``` clojure
bb '
(defn async-command [& args]
  (async/thread (apply shell/sh "bash" "-c" args)))

(-> (async/alts!! [(async-command "sleep 2 && echo process 1")
                   (async-command "sleep 1 && echo process 2")])
    first :out str/trim println)'
process 2
```

Note: the `go` macro is available for compatibility with JVM programs, but the
implementation maps to `clojure.core.async/thread` and the single exclamation
mark operations (`<!`, `>!`, etc.) map to the double exclamation mark operations
(`<!!`, `>!!`, etc.). It will not "park" threads, like on the JVM.

## HTTP

For making HTTP requests you can use:

- [babashka.curl](https://github.com/borkdude/babashka.curl). This library is
  included with babashka and aliased as `curl` in the user namespace.
- `slurp` for simple `GET` requests
- [clj-http-lite](https://github.com/borkdude/clj-http-lite) as a library.
- `clojure.java.shell` or `java.lang.ProcessBuilder` for shelling out to your
  favorite command line http client

### HTTP over Unix sockets

This can be useful for talking to Docker:

``` clojure
(require '[clojure.java.shell :refer [sh]])
(require '[cheshire.core :as json])
(-> (sh "curl" "--silent"
        "--no-buffer" "--unix-socket"
        "/var/run/docker.sock"
        "http://localhost/images/json")
    :out
    (json/parse-string true)
    first
    :RepoTags) ;;=> ["borkdude/babashka:latest"]
```

## Shutdown hook

Adding a shutdown hook allows you to execute some code before the script exits.

``` clojure
$ bb -e '(-> (Runtime/getRuntime) (.addShutdownHook (Thread. #(println "bye"))))'
bye
```

This also works when the script is interrupted with ctrl-c.

## JDBC

Babashka supports the [`next.jdbc`](https://github.com/seancorfield/next-jdbc)
library along with drivers for [PostgresQL](https://www.postgresql.org/) and
[HSQLDB](http://hsqldb.org/). These features are not part of the standard `bb`
distribution. See [doc/build.md](doc/build.md) for details on how to build
babashka with these features. See this
[test](test-resources/babashka/postgres_test.clj) for an example how to use
this.

Interacting with `psql`, `mysql` and the `sqlite` CLIs can be achieved by
shelling out. See the [examples](examples) directory.

## Bencode

Babashka comes with the [nrepl/bencode](https://github.com/nrepl/bencode)
library which allows you to read and write bencode messages to a socket. A
simple example which evaluates a Clojure expression on an nREPL server started
with `lein repl`:

``` clojure
(ns nrepl-client
  (:require [bencode.core :as b]))

(defn nrepl-eval [port expr]
  (let [s (java.net.Socket. "localhost" port)
        out (.getOutputStream s)
        in (java.io.PushbackInputStream. (.getInputStream s))
        _ (b/write-bencode out {"op" "eval" "code" expr})
        bytes (get (b/read-bencode in) "value")]
    (String. bytes)))

(nrepl-eval 52054 "(+ 1 2 3)") ;;=> "6"
```

## Differences with Clojure

Babashka is implemented using the [Small Clojure
Interpreter](https://github.com/borkdude/sci). This means that a snippet or
script is not compiled to JVM bytecode, but executed form by form by a runtime
which implements a sufficiently large subset of Clojure. Babashka is compiled to
a native binary using [GraalVM](https://github.com/oracle/graal). It comes with
a selection of built-in namespaces and functions from Clojure and other useful
libraries. The data types (numbers, strings, persistent collections) are the
same. Multi-threading is supported (`pmap`, `future`).

Differences with Clojure:

- A pre-selected set of Java classes are supported. You cannot add Java classes
  at runtime.

- Interpretation comes with overhead. Therefore tight loops are likely slower
  than in Clojure on the JVM. In general interpretation yields slower programs
  than compiled programs.

- No `defprotocol`, `defrecord` and unboxed math.

## [Libraries, pods and projects](doc/libraries.md)

A list of projects (scripts, libraries, pods and tools) known to work with babashka.

## Pods

Pods are programs that can be used as a Clojure library by
babashka. Documentation is available in the [library
repo](https://github.com/babashka/babashka.pods).

## Package babashka script as a AWS Lambda

AWS Lambda runtime doesn't support signals, therefore babashka has to disable
handling of the SIGPIPE. This can be done by setting
`BABASHKA_DISABLE_PIPE_SIGNAL_HANDLER` to `true`.

## Articles, podcasts and videos

- [Babashka Pods](https://www.youtube.com/watch?v=3Q4GUiUIrzg&feature=emb_logo) presentation by Michiel Borkent at the [Dutch Clojure Meetup](http://meetup.com/The-Dutch-Clojure-Meetup).
- [AWS Logs using Babashka](https://tech.toyokumo.co.jp/entry/aws_logs_babashka) a blog published by [toyokumo](https://toyokumo.co.jp/).
- [The REPL podcast](https://www.therepl.net/episodes/36/) Michiel Borkent talks about [clj-kondo](https://github.com/borkdude/clj-kondo), [Jet](https://github.com/borkdude/jet), Babashka, and [GraalVM](https://github.com/oracle/graal) with Daniel Compton.
- [Implementing an nREPL server for babashka](https://youtu.be/0YmZYnwyHHc): impromptu presentation by Michiel Borkent at the online [Dutch Clojure Meetup](http://meetup.com/The-Dutch-Clojure-Meetup)
- [ClojureScript podcast](https://soundcloud.com/user-959992602/s3-e5-babashka-with-michiel-borkent) with Jacek Schae interviewing Michiel Borkent
- [Babashka talk at ClojureD](https://www.youtube.com/watch?v=Nw8aN-nrdEk) ([slides](https://speakerdeck.com/borkdude/babashka-and-the-small-clojure-interpreter-at-clojured-2020)) by Michiel Borkent
- [Babashka: a quick example](https://juxt.pro/blog/posts/babashka.html) by Malcolm Sparks
- [Clojure Start Time in 2019](https://stuartsierra.com/2019/12/21/clojure-start-time-in-2019) by Stuart Sierra
- [Advent of Random
  Hacks](https://lambdaisland.com/blog/2019-12-19-advent-of-parens-19-advent-of-random-hacks)
  by Arne Brasseur
- [Clojure in the Shell](https://lambdaisland.com/blog/2019-12-05-advent-of-parens-5-clojure-in-the-shell) by Arne Brasseur
- [Clojure Tool](https://purelyfunctional.tv/issues/purelyfunctional-tv-newsletter-351-clojure-tool-babashka/) by Eric Normand

## [Building babashka](doc/build.md)

## [Developing Babashka](doc/dev.md)

## Including new libraries or classes

Before new libraries or classes go into the standardly distributed babashka
binary, these evaluation criteria are considered:

- The library or class is useful for general purpose scripting.
- Adding the library or class would make babashka more compatible with Clojure
  libraries relevant to scripting.
- The library cannot be interpreted by with babashka using `--classpath`.
- The functionality can't be met by shelling out to another CLI or can't be
  written as a small layer over an existing CLI (like `babashka.curl`) instead.

If not all of the criteria are met, but adding a feature is still useful to a
particular company or niche, adding it behind a feature flag is still a
possibility. This is currently the case for `next.jdbc` and the `PostgresQL` and
`HSQLDB` database drivers. Companies interested in these features can compile an
instance of babashka for their internal use. Companies are also free to make
forks of babashka and include their own internal libraries. If their customized
babashka is interesting to share with the world, they are free to distribute it
using a different binary name (like `bb-sql`, `bb-docker`, `bb-yourcompany`,
etc.). See the [feature flag documentation](doc/build.md#feature-flags) and the
implementation of the existing feature flags ([example
commit](https://github.com/borkdude/babashka/commit/02c7c51ad4b2b1ab9aa95c26a74448b138fe6659)).

## Related projects

- [planck](https://planck-repl.org/)
- [joker](https://github.com/candid82/joker)
- [closh](https://github.com/dundalek/closh)
- [lumo](https://github.com/anmonteiro/lumo)

## [Examples](doc/examples.md)

A collection of example scripts.

## Thanks

- [adgoji](https://www.adgoji.com/) for financial support
- [CircleCI](https://circleci.com/) for CI and additional support
- [Nikita Prokopov](https://github.com/tonsky) for the logo
- [contributors](https://github.com/borkdude/babashka/graphs/contributors) and
  other users posting issues with bug reports and ideas

## Contributors

### Code Contributors

This project exists thanks to all the people who contribute. [[Contribute](doc/dev.md)].
<a href="https://github.com/borkdude/babashka/graphs/contributors"><img src="https://opencollective.com/babashka/contributors.svg?width=890&button=false" /></a>

### Financial Contributors

Become a financial contributor and help us sustain our community. [[Contribute](https://opencollective.com/babashka/contribute)]

#### Individuals

<a href="https://opencollective.com/babashka"><img src="https://opencollective.com/babashka/individuals.svg?width=890"></a>

#### Organizations

Support this project with your organization. Your logo will show up here with a link to your website. [[Contribute](https://opencollective.com/babashka/contribute)]

<a href="https://opencollective.com/babashka/organization/0/website"><img src="https://opencollective.com/babashka/organization/0/avatar.svg"></a>
<a href="https://opencollective.com/babashka/organization/1/website"><img src="https://opencollective.com/babashka/organization/1/avatar.svg"></a>
<a href="https://opencollective.com/babashka/organization/2/website"><img src="https://opencollective.com/babashka/organization/2/avatar.svg"></a>
<a href="https://opencollective.com/babashka/organization/3/website"><img src="https://opencollective.com/babashka/organization/3/avatar.svg"></a>
<a href="https://opencollective.com/babashka/organization/4/website"><img src="https://opencollective.com/babashka/organization/4/avatar.svg"></a>
<a href="https://opencollective.com/babashka/organization/5/website"><img src="https://opencollective.com/babashka/organization/5/avatar.svg"></a>
<a href="https://opencollective.com/babashka/organization/6/website"><img src="https://opencollective.com/babashka/organization/6/avatar.svg"></a>
<a href="https://opencollective.com/babashka/organization/7/website"><img src="https://opencollective.com/babashka/organization/7/avatar.svg"></a>
<a href="https://opencollective.com/babashka/organization/8/website"><img src="https://opencollective.com/babashka/organization/8/avatar.svg"></a>
<a href="https://opencollective.com/babashka/organization/9/website"><img src="https://opencollective.com/babashka/organization/9/avatar.svg"></a>

## License

Copyright © 2019-2020 Michiel Borkent

Distributed under the EPL License. See LICENSE.

This project contains code from:
- Clojure, which is licensed under the same EPL License.
