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
scan use a bit of Clojure in their shell scripts, it may be useful.

Babashka runs as a binary with fast startup time and uses
[sci](https://github.com/borkdude/sci) for interpreting Clojure, which results
in faster startup times:

``` shellsession
$ time clj -e "(require '[clojure.java.shell :as shell])" ./download_html.clj
2.15s user 0.17s system 242% cpu 0.959 total

$ time bb -f ./download_html.clj
0.00s user 0.00s system 69% cpu 0.010 total
```

A trade off is that [sci](https://github.com/borkdude/sci) implements only a
subset of Clojure. If you need more, feel free to post an issue or check out
other Clojure scripting tools
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
bb [ --help ] [ -i ] [ -o ] [ -io ] [ --version ] [ -f <file> ] [ expression ]
```

Type `bb --help` to see a full explanation of the options.

The input is read as EDN by default. If the `-i` flag is provided, then the
input is read as a string which is then split on newlines. The output is printed
as EDN by default, unless the `-o` flag is provided, then the output is turned
into shell-scripting friendly output. To combine `-i` and `-o` you can use
`-io`.

The current version can be printed with `bb --version`.

Babashka supports a subset of Clojure which is interpreted by
[sci](https://github.com/borkdude/sci).

The `clojure.core` functions are accessible without a namespace alias.

The following Clojure namespaces are required by default and only available
through the aliases:

- `clojure.string` aliased as `str`
- `clojure.set` aliased as `set`
- `clojure.edn` aliased as `edn` (only `read-string` is available)
- `clojure.java.shell` aliases as `shell` (only `sh` is available)

From Java the following is available:

- `System`: `exit`, `getProperty`, `getProperties`, `getenv`

Special vars:

- `*in*`: contains the input read from stdin
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

Babashka expressions may be executed from a file using `-f` or `--file`:

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

Distributed under the EPL License, same as Clojure. See LICENSE.
