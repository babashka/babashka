# babashka

[![CircleCI](https://circleci.com/gh/borkdude/babashka/tree/master.svg?style=shield)](https://circleci.com/gh/borkdude/babashka/tree/master)
[![Clojars Project](https://img.shields.io/clojars/v/borkdude/babashka.svg)](https://clojars.org/borkdude/babashka)
[![cljdoc badge](https://cljdoc.org/badge/borkdude/babashka)](https://cljdoc.org/d/borkdude/babashka/CURRENT)

A sprinkle of Clojure for the command line.

## Quickstart

``` shellsession
$ bash <(curl -s https://raw.githubusercontent.com/borkdude/babashka/master/install)
$ bb '(vec (dedupe *in*))' <<< '[1 1 1 1 2]'
[1 2]
```

## Rationale

If you're a bash expert, you probably don't need this. But for those of us who
can use a bit of Clojure in their shell scripts, it may be useful.

Babashka runs as a binary and uses [sci](https://github.com/borkdude/sci) for
interpreting Clojure, which results in faster startup times:

``` shellsession
$ time clojure -e "(require '[clojure.java.shell :as shell])" ./download_html.clj
2.15s user 0.17s system 242% cpu 0.959 total

$ time bb -f ./download_html.clj
0.00s user 0.00s system 69% cpu 0.010 total
```

A trade-off is that [sci](https://github.com/borkdude/sci) implements only a
subset of Clojure. If you need more, feel free to post an issue or check out
other Clojure scripting
[projects](https://github.com/borkdude/babashka#related-projects).

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
bb [ --help ] | [ --version ] | ( [ -i ] [ -o ] | [ -io ] ) [ --stream ] ( expression | -f <file> )
```

Type `bb --help` to see a full explanation of the options.

The input is read as EDN by default. If the `-i` flag is provided, then the
input is read as a string which is then split on newlines. The output is printed
as EDN by default, unless the `-o` flag is provided, then the output is turned
into shell-scripting friendly output. To combine `-i` and `-o` you can use
`-io`. When using the `--stream` option the expression is executed for every
line or EDN value from stdin.

The `clojure.core` functions are accessible without a namespace alias.

The following Clojure namespaces are required by default and only available
through the aliases:

- `clojure.string` aliased as `str`
- `clojure.set` aliased as `set`
- `clojure.edn` aliased as `edn` (only `read-string` is available)
- `clojure.java.shell` aliases as `shell` (only `sh` is available)

From Java the following is available:

- `System`: `exit`, `getProperty`, `setProperty`, `getProperties`, `getenv`

Special vars:

- `*in*`: contains the input read from stdin (EDN by default, multiple lines with the `-i` option)
<!-- - `bb/*in*`: the unprocessed input from stdin -->
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
```

``` shellsession
$ bb '(#(+ %1 %2 %3) 1 2 *in*)' <<< 3
6
```

``` shellsession
$ ls | bb -i '(filterv #(re-find #"reflection" %) *in*)'
["reflection.json"]
```

``` shellsession
$ bb '(run! #(shell/sh "touch" (str "/tmp/test/" %)) (range 100))'
$ ls /tmp/test | bb -i '*in*'
["0" "1" "10" "11" "12" "13" "14" "15" "16" "17" "18" "19" "2" "20" "21" ...]
```

More examples can be found in the [gallery](#gallery).

## Running a file

Scripts may be executed from a file using `-f` or `--file`:

``` shellsession
bb -f download_html.clj
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

## Enabling SSL

If you want to be able to use SSL to e.g. run `(slurp
"https://www.clojure.org")` you will need to add the location where
`libsunec.so` or `libsunec.dylib` is located to the `java.library.path` Java
property. This library comes with most JVM installations, so you might already
have it on your machine. It is usually located in `<JAVA_HOME>/jre/lib` or
`<JAVA_HOME>/jre/<platform>/lib`. It is also bundled with GraalVM.

Example:

``` shellsession
$ cat /tmp/https_get.clj
#!/usr/bin/env bb -f

(System/setProperty
 "java.library.path"
 "/Library/Java/JavaVirtualMachines/adoptopenjdk-8.jdk/Contents/Home/jre/lib")

(slurp (first *command-line-args*))
```

``` shellsession
$ /tmp/https_get.clj https://www.google.com | bb '(subs *in* 0 50)'
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

## Support this project

Do you enjoy this project? Consider buying me a [hot
beverage](https://ko-fi.com/borkdude).

## License

Copyright © 2019 Michiel Borkent

Distributed under the EPL License, same as Clojure. See LICENSE.
