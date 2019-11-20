# babashka

[![CircleCI](https://circleci.com/gh/borkdude/babashka/tree/master.svg?style=shield)](https://circleci.com/gh/borkdude/babashka/tree/master)
[![Clojars Project](https://img.shields.io/clojars/v/borkdude/babashka.svg)](https://clojars.org/borkdude/babashka)
[![cljdoc badge](https://cljdoc.org/badge/borkdude/babashka)](https://cljdoc.org/d/borkdude/babashka/CURRENT)

A Clojure [babushka](https://en.wikipedia.org/wiki/Headscarf) for the grey areas of Bash.

## Quickstart

``` shellsession
$ bash <(curl -s https://raw.githubusercontent.com/borkdude/babashka/master/install)
$ ls | bb --time -i '(filter #(-> % io/file .isDirectory) *in*)'
("doc" "resources" "sci" "script" "src" "target" "test")
bb took 4ms.
```

## Rationale

The sweet spot for babashka is executing Clojure snippets or scripts in the same
space where you would use Bash.

As one user described it:

> I’m quite at home in Bash most of the time, but there’s a substantial grey area of things that are too complicated to be simple in bash, but too simple to be worth writing a clj/s script for. Babashka really seems to hit the sweet spot for those cases.

Goals:

* Fast startup / low latency. This is achieved by compiling to native using [GraalVM](https://github.com/oracle/graal).
* Familiarity and portability. Keep migration barriers between bash and Clojure as low as possible by:
  - Gradually introducing Clojure expressions to existing bash scripts
  - Scripts written in babashka should also be able to run on the JVM without major changes.
* Multi-threading support similar to Clojure on the JVM
* Batteries included (clojure.tools.cli, core.async, ...)

Non-goals:

* Performance
* Provide a mixed Clojure/bash DSL (see portability).
* Replace existing shells. Babashka is a tool you can use inside existing shells like bash and it is designed to play well with them. It does not aim to replace them.

Reasons why babashka may not be the right fit for your use case:

- It uses [sci](https://github.com/borkdude/sci) for interpreting Clojure. Sci
implements only a subset of Clojure and is not as performant as compiled code.
- External libraries are not available (although you may use `load-file` for
  loading external scripts).

Read more about the differences with Clojure [here](#differences-with-clojure).

## Status

Experimental. Breaking changes are expected to happen at this phase.

## Examples

``` shellsession
$ ls | bb -i '*in*'
["LICENSE" "README.md" "bb" "doc" "pom.xml" "project.clj" "reflection.json" "resources" "script" "src" "target" "test"]

$ ls | bb -i '(count *in*)'
12

$ bb '(vec (dedupe *in*))' <<< '[1 1 1 1 2]'
[1 2]

$ bb '(filterv :foo *in*)' <<< '[{:foo 1} {:bar 2}]'
[{:foo 1}]

$ bb '(#(+ %1 %2 %3) 1 2 *in*)' <<< 3
6

$ ls | bb -i '(filterv #(re-find #"reflection" %) *in*)'
["reflection.json"]

$ bb '(run! #(shell/sh "touch" (str "/tmp/test/" %)) (range 100))'
$ ls /tmp/test | bb -i '*in*'
["0" "1" "10" "11" "12" "13" "14" "15" "16" "17" "18" "19" "2" "20" "21" ...]

$ bb -O '(repeat "dude")' | bb --stream '(str *in* "rino")' | bb -I '(take 3 *in*)'
("duderino" "duderino" "duderino")
```

More examples can be found in the [gallery](#gallery).

## Installation

### Brew

Linux and macOS binaries are provided via brew.

Install:

    brew install borkdude/brew/babashka

Upgrade:

    brew upgrade babashka


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

You may also download a binary from [Github](https://github.com/borkdude/babashka/releases).

## Usage

``` shellsession
Usage: bb [ -i | -I ] [ -o | -O ] [ --stream ] ( -e <expression> | -f <file> | --socket-repl [<host>:]<port> )

Options:

  --help, -h or -?: print this help text.
  --version: print the current version of babashka.

  -i: bind *in* to a lazy seq of lines from stdin.
  -I: bind *in* to a lazy seq of EDN values from stdin.
  -o: write lines to stdout.
  -O: write EDN values to stdout.
  --stream: stream over lines or EDN values from stdin. Combined with -i or -I *in* becomes a single value per iteration.
  -e, --eval <expression>: evaluate an expression
  -f, --file <path>: evaluate a file
  --socket-repl: start socket REPL. Specify port (e.g. 1666) or host and port separated by colon (e.g. 127.0.0.1:1666).
  --time: print execution time before exiting.

If neither -e, -f, or --socket-repl are specified, then the first argument that is not parsed as a option is treated as a file if it exists, or as an expression otherwise.
Everything after that is bound to *command-line-args*.
```

The `clojure.core` functions are accessible without a namespace alias.

The following namespaces are required by default and available through the
pre-defined aliases. You may use `require` + `:as` and/or `:refer` on these
namespaces. If not all vars are available, they are enumerated explicitly.

- `clojure.string` aliased as `str`
- `clojure.set` aliased as `set`
- `clojure.edn` aliased as `edn`:
  - `read-string`
- `clojure.java.shell` aliases as `shell`:
  - `sh`
- `clojure.java.io` aliased as `io`:
  - `as-relative-path`, `copy`, `delete-file`, `file`
- [`clojure.core.async`](https://clojure.github.io/core.async/) aliased as
  `async`. The `alt` and `go` macros are not available but `alts!!` does work as
  it is a function.
- [`me.raynes.conch.low-level`](https://github.com/clj-commons/conch#low-level-usage)
  aliased as `conch`
- [`clojure.tools.cli`](https://github.com/clojure/tools.cli) aliased as `tools.cli`
- [`clojure.data.csv`](https://github.com/clojure/data.csv) aliased as `csv`

The following Java classes are available:

- `ArithmeticException`
- `AssertionError`
- `Boolean`
- `Class`
- `Double`
- `Exception`
- `clojure.lang.ExceptionInfo`
- `Integer`
- `java.io.File`
- `java.util.regex.Pattern`
- `String`
- `System`
- `Thread`
```

More classes can be added by request.

Special vars:

- `*in*`: contains the input read from stdin. EDN by default, multiple lines of
text with the `-i` option, or multiple EDN values with the `-I` option.
- `*command-line-args*`: contain the command line args

Additionally, babashka adds the following functions:

- `wait/wait-for-port`. Usage:

``` clojure
(wait/wait-for-port "localhost" 8080)
(wait/wait-for-port "localhost" 8080 {:timeout 1000 :pause 1000})
```

Waits for TCP connection to be available on host and port. Options map supports `:timeout` and `:pause`. If `:timeout` is provided and reached, `:default`'s value (if any) is returned. The `:pause` option determines the time waited between retries.

- `wait/wait-for-path`. Usage:

``` clojure
(wait/wait-for-path "/tmp/wait-path-test")
(wait/wait-for-path "/tmp/wait-path-test" {:timeout 1000 :pause 1000})
```

Waits for file path to be available. Options map supports `:default`, `:timeout` and `:pause`. If `:timeout` is provided and reached, `:default`'s value (if any) is returned. The `:pause` option determines the time waited between retries.

- `sig/pipe-signal-received?`. Usage:

``` clojure
(sig/pipe-signal-received?)
```

Returns true if `PIPE` signal was received. Example:

``` shellsession
$ bb '((fn [x] (println x) (when (not (sig/pipe-signal-received?)) (recur (inc x)))) 0)' | head -n2
1
2
```

## Running a file

Scripts may be executed from a file using `-f` or `--file`:

``` shellsession
bb -f download_html.clj
```

Files can also be loaded inline using `load-file`:

``` shellsession
bb '(load-file "script.clj")'
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

(System/exit 0)
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

Babashka supports reader conditionals using the `:bb` feature:

``` clojure
$ cat example.clj
#?(:clj (in-ns 'foo) :bb (println "babashka doesn't support in-ns yet!"))

$ ./bb example.clj
babashka doesn't support in-ns yet!
```

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
$ bb '(-> (foo *in*) bar)' <<< 1
6
```

You can also preload an entire file using `load-file`:

``` shellsession
export BABASHKA_PRELOADS='(load-file "my_awesome_prelude.clj")'
```

Note that `*in*` is not available in preloads.

## Socket REPL

Start the socket REPL like this:

``` shellsession
$ bb --socket-repl 1666
Babashka socket REPL started at localhost:1666
```

Now you can connect with your favorite socket REPL client:

``` shellsession
$ rlwrap nc 127.0.0.1 1666
Babashka v0.0.14 REPL.
Use :repl/quit or :repl/exit to quit the REPL.
Clojure rocks, Bash reaches.

bb=> (+ 1 2 3)
6
bb=> :repl/quit
$
```

A socket REPL client for Emacs is
[inf-clojure](https://github.com/clojure-emacs/inf-clojure).

## Spawning and killing a process

You may use the `conch` namespace for this. It maps to
[`me.raynes.conch.low-level`](https://github.com/clj-commons/conch#low-level-usage).

Example:

``` clojure
$ bb '
(def ws (conch/proc "python" "-m" "SimpleHTTPServer" "1777"))
(net/wait-for-it "localhost" 1777) (conch/destroy ws)'
```

## Async

Apart from `future` for creating threads and the `conch` namespace for creating
processes, you may use the `async` namespace, which maps to `clojure.core.async`,  for asynchronous scripting. The following
example shows how to get first available value from two different processes:

``` clojure
bb '
(defn async-command [& args]
  (async/thread (apply shell/sh "bash" "-c" args)))

(-> (async/alts!! [(async-command "sleep 2 && echo process 1")
                   (async-command "sleep 1 && echo process 2")])
    first :out str/trim println)'
process 2
```

## Differences with Clojure

Babashka is implemented using the [Small Clojure
Interpreter](https://github.com/borkdude/sci). This means that a snippet or
script is not compiled to JVM bytecode, but executed form by form by a runtime
which implements a subset of Clojure. Babashka is compiled to a native binary
using [GraalVM](https://github.com/oracle/graal). It comes with a selection of
built-in namespaces and functions from Clojure and other useful libraries. The
data types (numbers, strings, persistent collections) are the
same. Multi-threading is supported (`pmap`, `future`).

Differences with Clojure:

- No user defined namespaces. Since this tool focuses on snippets and small
  scripts, there hasn't been a need to implement it yet.

- There is no `ns` macro for the same reason as above.

- No first class vars. Note that you can define and redefine global values with
`def` / `defn`, but there is no `var` indirection.

- A subset of Java classes are supported.

- Only the `clojure.core`, `clojure.set` and `clojure.string` namespaces are
  available from Clojure.

- There is no classpath and no support for loading code from Maven/Clojars
  dependencies. However, you can use `load-file` to load external code from
  disk.

- `require` does not load files; it only provides a way to create different
  aliases for included namespaces, which makes it easier to make scripts portable between the JVM and babashka.

- Interpretation comes with overhead. Therefore tight loops are likely slower
  than in Clojure on the JVM.

- No support for unboxed types.

## Developing Babashka

To work on Babashka itself make sure Git submodules are checked out.

``` shellsession
$ git clone https://github.com/borkdude/babashka --recursive
```

To update later on:

``` shellsession
$ git submodule update --recursive
```

You need [Leiningen](https://leiningen.org/), and for building binaries you need GraalVM.

### REPL

`lein repl` will get you a standard REPL/nREPL connection. To work on tests use `lein with-profiles +test repl`.

### Test

Test on the JVM (for development):

    script/test

Test the native version:

    BABASHKA_TEST_ENV=native script/test

### Build

To build this project, set `$GRAALVM_HOME` to the GraalVM distribution directory.

Then run:

    script/compile

## Related projects

- [planck](https://planck-repl.org/)
- [joker](https://github.com/candid82/joker)
- [closh](https://github.com/dundalek/closh)
- [lumo](https://github.com/anmonteiro/lumo)

## Gallery

Here's a gallery of more useful examples. Do you have a useful example? PR
welcome!

### Delete a list of files returned by a Unix command

```
find . | grep conflict | bb -i '(doseq [f *in*] (.delete (io/file f)))'
```

### Shuffle the lines of a file

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

### Fetch latest Github release tag

For converting JSON to EDN, see [jet](https://github.com/borkdude/jet).

``` shellsession
$ curl -s https://api.github.com/repos/borkdude/babashka/tags |
jet --from json --keywordize --to edn |
bb '(-> *in* first :name (subs 1))'
"0.0.4"
```

### Get latest OS-specific download url from Github

``` shellsession
$ curl -s https://api.github.com/repos/borkdude/babashka/releases |
jet --from json --keywordize |
bb '(-> *in* first :assets)' |
bb '(some #(re-find #".*linux.*" (:browser_download_url %)) *in*)'
"https://github.com/borkdude/babashka/releases/download/v0.0.4/babashka-0.0.4-linux-amd64.zip"
```

### View download statistics from Clojars

``` shellsession
$ curl https://clojars.org/stats/all.edn |
bb -o '(for [[[group art] counts] *in*] (str (reduce + (vals counts))  " " group "/" art))' |
sort -rn |
less
14113842 clojure-complete/clojure-complete
9065525 clj-time/clj-time
8504122 cheshire/cheshire
...
```

## Thanks

- [adgoji](https://www.adgoji.com/) for financial support

## License

Copyright © 2019 Michiel Borkent

Distributed under the EPL License. See LICENSE.

This project contains code from:
- Clojure, which is licensed under the same EPL License.
- [conch](https://github.com/clj-commons/conch), which is licensed under the
same EPL License.
