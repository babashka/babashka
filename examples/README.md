# Examples

- [Examples](#examples)
  - [Delete a list of files returned by a Unix command](#delete-a-list-of-files-returned-by-a-unix-command)
  - [Calculate aggregate size of directory](#calculate-aggregate-size-of-directory)
  - [Shuffle the lines of a file](#shuffle-the-lines-of-a-file)
  - [Fetch latest Github release tag](#fetch-latest-github-release-tag)
  - [Generate deps.edn entry for a gitlib](#generate-depsedn-entry-for-a-gitlib)
  - [View download statistics from Clojars](#view-download-statistics-from-clojars)
  - [Portable tree command](#portable-tree-command)
  - [List outdated maven dependencies](#list-outdated-maven-dependencies)
  - [Convert project.clj to deps.edn](#convert-projectclj-to-depsedn)
  - [Print current time in California](#print-current-time-in-california)
  - [Tiny http server](#tiny-http-server)
  - [Print random docstring](#print-random-docstring)
  - [Cryptographic hash](#cryptographic-hash)
  - [Package script as Docker image](#package-script-as-docker-image)
  - [Extract single file from zip](#extract-single-file-from-zip)
  - [Note taking app](#note-taking-app)
  - [which](#which)
  - [pom.xml version](#pomxml-version)
  - [Whatsapp frequencies](#whatsapp-frequencies)
  - [Find unused vars](#find-unused-vars)
  - [List contents of jar file](#list-contents-of-jar-file)
  - [Invoke vim inside a script](#invoke-vim-inside-a-script)
  - [Portal](#portal)
  - [Image viewer](#image-viewer)
  - [HTTP server](#http-server)
  - [Torrent viewer](#torrent-viewer)
  - [cprop.clj](#cpropclj)
  - [fzf](#fzf)
  - [digitalocean-ping.clj](#digitalocean-pingclj)
  - [download-aliases.clj](#download-aliasesclj)
  - [Is TTY?](#is-tty)
  - [normalize-keywords.clj](#normalize-keywordsclj)
  - [Check stdin for data](#check-stdin-for-data)
  - [Using org.clojure/data.xml](#using-orgclojuredataxml)
  - [Simple logger](#simple-logger)
  - [Using GZip streams (memo utility)](#using-gzip-streams-to-make-a-note-utility)
  - [Pretty-printing mySQL results](#pretty-printing-mysql-results)
  - [Single page application with Babashka + htmx](#single-page-application-with-babashka--htmx)
  - [Wikipedia translation](#wikipedia-translation)
  

Here's a gallery of useful examples. Do you have a useful example? PR welcome!

## Delete a list of files returned by a Unix command

```
find . | grep conflict | bb -i '(doseq [f *input*] (.delete (io/file f)))'
```

## Calculate aggregate size of directory

``` clojure
#!/usr/bin/env bb

(as-> (io/file (or (first *command-line-args*) ".")) $
  (file-seq $)
  (map #(.length %) $)
  (reduce + $)
  (/ $ (* 1024 1024))
  (println (str (int $) "M")))
```

``` shellsession
$ dir-size
130M

$ dir-size ~/Dropbox/bin
233M
```

## Shuffle the lines of a file

``` shellsession
$ cat /tmp/test.txt
1 Hello
2 Clojure
3 Babashka
4 Goodbye

$ < /tmp/test.txt bb -io '(shuffle *input*)'
3 Babashka
2 Clojure
4 Goodbye
1 Hello
```

## Fetch latest Github release tag

``` shell
(require '[clojure.java.shell :refer [sh]]
         '[cheshire.core :as json])

(defn babashka-latest-version []
  (-> (sh "curl" "https://api.github.com/repos/babashka/babashka/tags")
      :out
      (json/parse-string true)
      first
      :name))

(babashka-latest-version) ;;=> "v0.0.73"
```

## Generate deps.edn entry for a gitlib

``` clojure
#!/usr/bin/env bb

(require '[clojure.java.shell :refer [sh]]
         '[clojure.string :as str])

(let [[username project branch] *command-line-args*
      branch (or branch "master")
      url (str "https://github.com/" username "/" project)
      sha (-> (sh "git" "ls-remote" url branch)
              :out
              (str/split #"\s")
              first)]
  {:git/url url
   :sha sha})
```

``` shell
$ gitlib.clj nate fs
{:git/url "https://github.com/nate/fs", :sha "75b9fcd399ac37cb4f9752a4c7a6755f3fbbc000"}
$ clj -Sdeps "{:deps {fs $(gitlib.clj nate fs)}}" \
  -e "(require '[nate.fs :as fs]) (fs/creation-time \".\")"
#object[java.nio.file.attribute.FileTime 0x5c748168 "2019-07-05T14:06:26Z"]
```

## View download statistics from Clojars

Contributed by [@plexus](https://github.com/plexus).

``` shellsession
$ curl https://clojars.org/stats/all.edn |
bb -o '(for [[[group art] counts] *input*] (str (reduce + (vals counts))  " " group "/" art))' |
sort -rn |
less
14113842 clojure-complete/clojure-complete
9065525 clj-time/clj-time
8504122 cheshire/cheshire
...
```

## Portable tree command

See [examples/tree.clj](https://github.com/babashka/babashka/blob/master/examples/tree.clj).

``` shellsession
$ clojure -Sdeps '{:deps {org.clojure/tools.cli {:mvn/version "0.4.2"}}}' examples/tree.clj src
src
└── babashka
    ├── impl
    │   ├── tools
    │   │   └── cli.clj
...

$ examples/tree.clj src
src
└── babashka
    ├── impl
    │   ├── tools
    │   │   └── cli.clj
...
```

## List outdated maven dependencies

See [examples/outdated.clj](https://github.com/babashka/babashka/blob/master/examples/outdated.clj).
Inspired by an idea from [@seancorfield](https://github.com/seancorfield).

``` shellsession
$ cat /tmp/deps.edn
{:deps {cheshire {:mvn/version "5.8.1"}
        clj-http {:mvn/version "3.4.0"}}}

$ examples/outdated.clj /tmp/deps.edn
clj-http/clj-http can be upgraded from 3.4.0 to 3.10.0
cheshire/cheshire can be upgraded from 5.8.1 to 5.9.0
```

## Convert project.clj to deps.edn

Contributed by [@plexus](https://github.com/plexus).

``` shellsession
$ cat project.clj |
sed -e 's/#=//g' -e 's/~@//g' -e 's/~//g' |
bb '(let [{:keys [dependencies source-paths resource-paths]} (apply hash-map (drop 3 *input*))]
  {:paths (into source-paths resource-paths)
   :deps (into {} (for [[d v] dependencies] [d {:mvn/version v}]))}) ' |
jet --pretty > deps.edn
```

A script with the same goal can be found [here](https://gist.github.com/swlkr/3f346c66410e5c60c59530c4413a248e#gistcomment-3232605).

## Print current time in California

See [examples/pst.clj](https://github.com/babashka/babashka/blob/master/examples/pst.clj)

## Tiny http server

This implements an http server from scratch. Note that babashka comes with `org.httpkit.server` now, so you don't need to build an http server from scratch anymore.

See [examples/http_server_from_scratch.clj](https://github.com/babashka/babashka/blob/master/examples/http_server_from_scratch.clj)

Original by [@souenzzo](https://gist.github.com/souenzzo/a959a4c5b8c0c90df76fe33bb7dfe201)

## Print random docstring

See [examples/random_doc.clj](https://github.com/babashka/babashka/blob/master/examples/random_doc.clj)

``` shell
$ examples/random_doc.clj
-------------------------
clojure.core/ffirst
([x])
  Same as (first (first x))
```

## Cryptographic hash

`sha1.clj`:
``` clojure
#!/usr/bin/env bb

(defn sha1
  [s]
  (let [hashed (.digest (java.security.MessageDigest/getInstance "SHA-1")
                        (.getBytes s))
        sw (java.io.StringWriter.)]
    (binding [*out* sw]
      (doseq [byte hashed]
        (print (format "%02X" byte))))
    (str sw)))

(sha1 (first *command-line-args*))
```

``` shell
$ sha1.clj babashka
"0AB318BE3A646EEB1E592781CBFE4AE59701EDDF"
```

## Package script as Docker image

`Dockerfile`:
``` dockerfile
FROM babashka/babashka
RUN echo $'\
(println "Your command line args:" *command-line-args*)\
'\
>> script.clj

ENTRYPOINT ["bb", "script.clj"]
```

``` shell
$ docker build . -t script
...
$ docker run --rm script 1 2 3
Your command line args: (1 2 3)
```

## Extract single file from zip

``` clojure
;; Given the following:

;; $ echo 'contents' > file
;; $ zip zipfile.zip file
;; $ rm file

;; we extract the single file from the zip archive using java.nio:

(import '[java.nio.file Files FileSystems CopyOption])
(let [zip-file (io/file "zipfile.zip")
      file (io/file "file")
      fs (FileSystems/newFileSystem (.toPath zip-file) nil)
      file-in-zip (.getPath fs "file" (into-array String []))]
  (Files/copy file-in-zip (.toPath file)
              (into-array CopyOption [])))
```

## Note taking app

See
[examples/notes.clj](https://github.com/babashka/babashka/blob/master/examples/notes.clj). This
is a variation on the
[http-server](https://github.com/babashka/babashka/#tiny-http-server)
example. If you get prompted with a login, use `admin`/`admin`.

## which

The `which` command re-implemented in Clojure. See
[examples/which.clj](https://github.com/babashka/babashka/blob/master/examples/which.clj).
Prints the canonical file name.

``` shell
$ examples/which.clj rg
/usr/local/Cellar/ripgrep/11.0.1/bin/rg
```

## pom.xml version

A script to retrieve the version from a `pom.xml` file. See
[pom_version_get.clj](pom_version_get.clj). Written by [@wilkerlucio](https://github.com/wilkerlucio).

See [pom_version_get_xml_zip.clj](pom_version_get_xml_zip.clj) for how to do the same using zippers.

Also see [pom_version_set.clj](pom_version_set.clj) to set the pom version.

## Whatsapp frequencies

Show frequencies of messages by user in Whatsapp group chats.
See [examples/whatsapp_frequencies.clj](whatsapp_frequencies.clj)

## Find unused vars

[This](hsqldb_unused_vars.clj) script invokes clj-kondo, stores
returned data in an in memory HSQLDB database and prints the result of a query
which finds unused vars. It uses
[pod-babashka-hsqldb](https://github.com/borkdude/pod-babashka-hsqldb).

``` shell
$ bb examples/hsqldb_unused_vars.clj src

|                   :VARS/NS |               :VARS/NAME |                     :VARS/FILENAME | :VARS/ROW | :VARS/COL |
|----------------------------|--------------------------|------------------------------------|-----------|-----------|
| babashka.impl.bencode.core |           read-netstring | src/babashka/impl/bencode/core.clj |       162 |         1 |
| babashka.impl.bencode.core |          write-netstring | src/babashka/impl/bencode/core.clj |       201 |         1 |
|      babashka.impl.classes | generate-reflection-file |      src/babashka/impl/classes.clj |       230 |         1 |
|    babashka.impl.classpath |      ->DirectoryResolver |    src/babashka/impl/classpath.clj |        12 |         1 |
|    babashka.impl.classpath |        ->JarFileResolver |    src/babashka/impl/classpath.clj |        37 |         1 |
|    babashka.impl.classpath |                 ->Loader |    src/babashka/impl/classpath.clj |        47 |         1 |
| babashka.impl.clojure.test |            file-position | src/babashka/impl/clojure/test.clj |       286 |         1 |
| babashka.impl.nrepl-server |             stop-server! | src/babashka/impl/nrepl_server.clj |       179 |         1 |
|              babashka.main |                    -main |              src/babashka/main.clj |       485 |         1 |
```

## List contents of jar file

For the code see [examples/ls_jar.clj](ls_jar.clj).

``` shell
$ ls_jar.clj borkdude/sci 0.0.13-alpha.24
META-INF/MANIFEST.MF
META-INF/maven/borkdude/sci/pom.xml
META-INF/leiningen/borkdude/sci/project.clj
...
```

## Invoke vim inside a script

See [examples/vim.clj](vim.clj).

## Portal

This script uses [djblue/portal](https://github.com/djblue/portal/) for inspecting EDN, JSON, XML or YAML files.

Example usage:

``` shell
$ examples/portal.clj ~/git/clojure/pom.xml
```

See [portal.clj](portal.clj).

## Image viewer

Opens browser window and lets user navigate through images of all sub-directories.

Example usage:

``` shell
$ examples/image-viewer.clj
```

See [image-viewer.clj](image-viewer.clj).

## HTTP Server

Opens browser window and lets user navigate through filesystem, similar to
`python3 -m http.server`.

Example usage:

``` shell
$ examples/http-server.clj
```

See [http-server.clj](http-server.clj).

## Torrent viewer

Shows the content of a torrent file. Note that pieces' content is hidden.

Example usage:
``` shell
$ examples/torrent-viewer.clj file.torrent
```

See [torrent-viewer.clj](torrent-viewer.clj).

## [cprop.clj](cprop.clj)

This script uses [tolitius/cprop](https://github.com/tolitius/cprop) library.

See [cprop.clj](cprop.clj)

Example usage:

```shell
$ ( cd examples && bb cprop.clj )
```

## [fzf](fzf.clj)

Invoke [fzf](https://github.com/junegunn/fzf), a command line fuzzy finder, from babashka.

See [fzf.clj](fzf.clj)

Example usage:

``` shell
$ cat src/babashka/main.clj | bb examples/fzf.clj
```

## [digitalocean-ping.clj](digitalocean-ping.clj)

The script allows to define which DigitalOcean cloud datacenter (region) has best network performance (ping latency).

See [digitalocean-ping.clj](digitalocean-ping.clj)

Example usage:

``` shell
$ bb digitalocean-ping.clj
```

## [download-aliases.clj](download-aliases.clj)

Download deps for all aliases in a deps.edn project.

## [Is TTY?](is_tty.clj)

An equivalent of Python's `os.isatty()` in Babashka, to check if the
`stdin`/`stdout`/`stderr` is connected to a TTY or not (useful to check if the
script output is being redirect to `/dev/null`, for example).

Only works in Unix systems.

``` shell
$ bb is-tty.clj
STDIN is TTY?: true
STDOUT is TTY?: true
STDERR is TTY?: true

$ bb is-tty.clj </dev/null
STDIN is TTY?: false
STDOUT is TTY?: true
STDERR is TTY?: true

$ bb is-tty.clj 1>&2 >/dev/null
STDIN is TTY?: true
STDOUT is TTY?: false
STDERR is TTY?: true

$ bb is-tty.clj 2>/dev/null
STDIN is TTY?: true
STDOUT is TTY?: true
STDERR is TTY?: false
```

## [normalize-keywords.clj](normalize-keywords.clj)

Provide a Clojure file to the script and it will print the Clojure file with
auto-resolved keywords normalized to fully qualified ones without double colons:
`::set/foo` becomes `:clojure.set/foo`.

``` clojure
$ cat /tmp/test.clj
(ns test (:require [clojure.set :as set]))

[::set/foo ::bar]

$ bb examples/normalize-keywords.clj /tmp/test.clj
(ns test (:require [clojure.set :as set]))

[:clojure.set/foo :test/bar]
```

## Check stdin for data

```shell
# when piping something in, we get a positive number
$ echo 'abc' | bb '(pos? (.available System/in))'
true
# even if we echo an empty string, we still get the newline
$ echo '' | bb '(pos? (.available System/in))'
true
# with nothing passed in, we finally return false
$ bb '(pos? (.available System/in))'
false
```

## Using org.clojure/data.xml

[xml-example.clj](xml-example.clj) explores some of the capabilities provided
by the `org.clojure/data.xml` library (required as `xml` by default in Babashka). 
While running the script will show some output, reading the file shows the library 
in use.

```shell
$ bb examples/xml-example.clj
... some vaguely interesting XML manipulation output
```

## Simple logger

[logger.clj](logger.clj) is a simple logger that works in bb.

``` clojure
$ bb "(require 'logger) (logger/log \"the logger says hi\")"
<expr>:1:19 the logger says hi 
```

## Using GZip streams to make a note utility

[memo.clj](memo.clj) creates zip files in /tmp for stashing notes (possibly the most inefficient KV store ever)

```shell
$ echo "8675309" | memo.clj put jenny
ok
$ memo.clj get jenny
8675309
```

## Pretty-printing mySQL results

[db_who.clj](db_who.clj) will query mysql for all the connected sessions and pretty-print the user and what program they're using.

```
$ bb db_who.clj
|             user |   program_name |
|------------------+----------------|
|   root@localhost |          mysql |
| fred@192.168.1.2 |      workbench |
| jane@192.168.1.3 | Toad for mySQL |
```
## Single page application with Babashka + htmx

Example of a todo list SPA using Babashka and htmx
See [htmx_todoapp.clj](htmx_todoapp.clj)

Contributed by [@prestancedesign](https://github.com/prestancedesign).

## Wikipedia translation

[wiki-translate.clj](wiki-translate.clj) uses Wikipedia to translate words from English to Dutch (other languages are available).

``` shell
$ ./wiki-translate.clj window
"Venster (muur) – Dutch"
```

Shared by Janne Himanka on Clojurians Slack
