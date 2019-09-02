# babashka

[![CircleCI](https://circleci.com/gh/borkdude/babashka/tree/master.svg?style=shield)](https://circleci.com/gh/borkdude/babashka/tree/master)
[![Clojars Project](https://img.shields.io/clojars/v/borkdude/babashka.svg)](https://clojars.org/borkdude/babashka)
[![cljdoc badge](https://cljdoc.org/badge/borkdude/babashka)](https://cljdoc.org/d/borkdude/babashka/CURRENT)

A sprinkle of Clojure for the command line.

## Quickstart

``` shellsession
$ bash <(curl -s https://raw.githubusercontent.com/borkdude/babashka/master/install)
$ ls | bb --time -i '(filter #(-> % io/file .isDirectory) *in*)'
("doc" "resources" "sci" "script" "src" "target" "test")
bb took 4ms.
```

## Rationale

If you're a bash expert, you probably don't need this. But for those of us who
can use a bit of Clojure in their shell scripts, it may be useful.

Babashka runs as a [GraalVM](https://github.com/oracle/graal) binary which
results in fast startup times:

``` shellsession
$ time clojure -e "(+ 1 2 3)"
6
clojure -e "(+ 1 2 3)"  3.29s user 0.32s system 99% cpu 3.638 total

$ time planck -e '(+ 1 2 3)'
6
plk -e '(+ 1 2 3)'  1.34s user 0.16s system 127% cpu 1.172 total

$ time bb '(+ 1 2 3)'
6
bb '(+ 1 2 3)'  0.01s user 0.01s system 37% cpu 0.046 total
```

It uses [sci](https://github.com/borkdude/sci) for interpreting Clojure. A
trade-off is that [sci](https://github.com/borkdude/sci) implements only a
subset of Clojure. Also, execution time may be slower than Clojure on the JVM or
(self-hosted) ClojureScript for more CPU-intensive calculations like:

``` shellsession
(last (take 1000000 (repeatedly #(+ 1 2 3))))
```

This would take 5 seconds using babashka, around half a second using self-hosted
ClojureScript and around 200ms in Clojure on the JVM.

So the sweet spot for babashka is executing tasks from the command line where
fast startup time is preferred, in the same space where you would use bash.

Where it can, babashka calls the regular implementation of Clojure on the JVM
and proxies common Java packages like `System` and `File`, so writing code in it
should be familiar if you're already using Clojure on the JVM.

## Status

Experimental. Breaking changes are expected to happen at this phase.

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
Usage: bb [ -i | -I ] [ -o | -O ] [ --stream ] ( expression | -f <file> | --socket-repl [host:]port )

Options:

  --help, -h or -?: print this help text.
  --version: print the current version of babashka.

  -i: bind *in* to a lazy seq of lines from stdin.
  -I: bind *in* to a lazy seq of EDN values from stdin.
  -o: write lines to stdout.
  -O: write EDN values to stdout.
  --stream: stream over lines or EDN values from stdin. Combined with -i or -I *in* becomes a single value per iteration.
  --file or -f: read expressions from file instead of argument wrapped in an implicit do.
  --socket-repl: start socket REPL. Specify port (e.g. 1666) or host and port separated by colon (e.g. 127.0.0.1:1666).
  --time: print execution time before exiting.
```

The `clojure.core` functions are accessible without a namespace alias.

The following Clojure namespaces are required by default and only available
through the aliases. If not all vars are available, they are enumerated
explicitly.

- `clojure.string` aliased as `str`
- `clojure.set` aliased as `set`
- `clojure.edn` aliased as `edn`:
  - `read-string`
- `clojure.java.shell` aliases as `shell`:
  - `sh`
- `clojure.java.io` aliased as `io`:
  - `as-relative-path`, `copy`, `delete-file`, `file`

From Java the following is available:

- `System`: `exit`, `getProperty`, `setProperty`, `getProperties`, `getenv`
- `File`: `.canRead`, `.canWrite`, `.delete`, `.deleteOnExit`, `.exists`,
  `.getAbsoluteFile`, `.getCanonicalFile`, `.getCanonicalPath`, `.getName`,
  `.getParent`, `.getParentFile`, `.getPath`, `.isAbsolute`, `.isDirectory`,
  `.isFile`, `.isHidden`, `.lastModified`, `.length`, `.list`, `.listFiles`,
  `.mkdir`, `.mkdirs`, `.renameTo`, `.setLastModified`, `.setReadOnly`,
  `.setReadable`, `.toPath`, `.toURI`.

Special vars:

- `*in*`: contains the input read from stdin. EDN by default, multiple lines of
text with the `-i` option, or multiple EDN values with the `-I` option.
- `*command-line-args*`: contain the command line args

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
#!/usr/bin/env bb -f

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

## Enabling SSL

If you want to be able to use SSL to e.g. run `(slurp
"https://www.clojure.org")` you will need to add the location where
`libsunec.so` or `libsunec.dylib` is located to the `java.library.path` Java
property. This library comes with most JVM installations, so you might already
have it on your machine. It is usually located in `<JAVA_HOME>/jre/lib` or
`<JAVA_HOME>/jre/<platform>/lib`. It is also bundled with GraalVM.

Example:

``` shellsession
$ export BABASHKA_PRELOADS="(System/setProperty \"java.library.path\" \"$JAVA_HOME/jre/lib\")"
$ bb '(slurp "https://www.clojure.org")' | bb '(subs *in* 0 50)'
"<!doctype html><html itemscope=\"\" itemtype=\"http:/"
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

This repo contains a submodule, so you will have clone that too.  If you're
doing that for the first time:

``` shellsession
$ git submodule update --init --recursive
```

and for subsequent updates:

``` shellsession
$ git submodule update --recursive
```

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

## License

Copyright © 2019 Michiel Borkent

Distributed under the EPL License. This project contains modified Clojure code
which is licensed under the same EPL License. See LICENSE.
