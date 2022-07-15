<img src="logo/babashka.svg" width="425px">

[![CircleCI](https://circleci.com/gh/babashka/babashka/tree/master.svg?style=shield)](https://circleci.com/gh/babashka/babashka/tree/master)
[![project chat](https://img.shields.io/badge/slack-join_chat-brightgreen.svg)](https://app.slack.com/client/T03RZGPFR/CLX41ASCS)
[![Financial Contributors on Open Collective](https://opencollective.com/babashka/all/badge.svg?label=financial+contributors)](https://opencollective.com/babashka) [![Clojars Project](https://img.shields.io/clojars/v/babashka/babashka.svg)](https://clojars.org/babashka/babashka)
[![twitter](https://img.shields.io/badge/twitter-%23babashka-blue)](https://twitter.com/search?q=%23babashka&src=typed_query&f=live)
[![docs](https://img.shields.io/badge/website-docs-blue)](https://book.babashka.org)

<blockquote class="twitter-tweet" data-lang="en">
    <p lang="en" dir="ltr">Life's too short to remember how to write Bash code. I feel liberated.</p>
    &mdash;
    <a href="https://github.com/laheadle">@laheadle</a> on Clojurians Slack
</blockquote>

<hr>

Please leave some feedback about babashka in the [Q1 Survey](https://forms.gle/ko3NjDg2SwXeEoNQ9)!

<hr>

## Introduction

Babashka is a native Clojure interpreter for scripting with fast startup. Its
main goal is to leverage Clojure in places where you would be using bash
otherwise.

As one user described it:

> I’m quite at home in Bash most of the time, but there’s a substantial grey area of things that are too complicated to be simple in bash, but too simple to be worth writing a clj/s script for. Babashka really seems to hit the sweet spot for those cases.

### Goals

* **Fast starting** Clojure scripting alternative for JVM Clojure
* **Easy installation:** grab the self-contained binary and run. No JVM needed.
* **Familiar:** targeted at JVM Clojure users
* **Cross-platform:** supports linux, macOS and Windows
* **Interop** with commonly used classes (`System`, `File`, `java.time.*`, `java.nio.*`)
* **Multi-threading** support (`pmap`, `future`)
* **Batteries included** (tools.cli, cheshire, ...)

### Non-goals

* Provide a mixed Clojure/Bash DSL (see portability).
* Replace existing shells. Babashka is a tool you can use inside existing shells like bash and it is designed to play well with them. It does not aim to replace them.

## Quickstart

For installation options check [Installation](https://github.com/babashka/babashka#installation).
For quick installation use:

``` shell
$ bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)
```

or grab a binary from [Github
releases](https://github.com/babashka/babashka/releases) yourself and place it
anywhere on the path.

Then you're ready to go:

``` shellsession
$ ls | bb -i '(filter #(-> % io/file .isDirectory) *input*)'
("doc" "resources" "sci" "script" "src" "target" "test")
bb took 4ms.
```

## Babashka users

See [companies](doc/companies.md) for a list of companies using babashka.

Are you using babashka in your company or personal projects? Let us know
[here](https://github.com/babashka/babashka/issues/254).

## Setting expectations

Babashka uses [SCI](https://github.com/borkdude/sci) for interpreting
Clojure. SCI implements a substantial subset of Clojure. Interpreting code is in
general not as performant as executing compiled code. If your script takes more
than a few seconds to run or has lots of loops, Clojure on the JVM may be a
better fit as the performance on JVM is going to outweigh its startup time
penalty. Read more about the differences with Clojure
[here](#differences-with-clojure).

## Status

Functionality regarding `clojure.core` and `java.lang` can be considered stable
and is unlikely to change. Changes may happen in other parts of babashka,
although we will try our best to prevent them. Always check the release notes or
[CHANGELOG.md](CHANGELOG.md) before upgrading.

### Talk

To get an overview of babashka, you can watch this talk ([slides](https://speakerdeck.com/borkdude/babashka-and-the-small-clojure-interpreter-at-clojured-2020)):

[![Babashka at ClojureD 2020](https://img.youtube.com/vi/Nw8aN-nrdEk/0.jpg)](https://www.youtube.com/watch?v=Nw8aN-nrdEk)

## Babashka book

The [babashka book](https://book.babashka.org) contains detailed information
about how to get the most out of babashka scripting.

## Examples

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

Read more about input and output flags [here](https://book.babashka.org/#_input_and_output_flags).

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

More examples can be found [here](examples/README.md).

## Try online

You can try babashka online with Nextjournal's babashka [notebook
environment](http://nextjournal.com/try/babashka?cm6=1).

## Installation

### Brew

Linux and macOS binaries are provided via brew.

Install:

    brew install borkdude/brew/babashka

<!-- On macOS with an M1 processor: -->

<!--     softwareupdate --install-rosetta -->
<!--     arch -x86_64 brew install borkdude/brew/babashka -->

Upgrade:

    brew upgrade babashka

### Nix

Linux and macOS (including ARM Macs) binaries are provided via nix (see the installation instructions for nix [here](https://nixos.org/download.html)).

Install:

    # Adding `nixpkgs-unstable` channel for more up-to-date binaries, skip this if you already have `nixpkgs-unstable` in your channel list
    nix-channel --add https://nixos.org/channels/nixpkgs-unstable nixpkgs-unstable
    nix-channel --update
    nix-env -iA nixpkgs-unstable.babashka

Upgrade:

    nix-channel --update
    nix-env -iA nixpkgs-unstable.babashka

You can find more documentation on how to use babashka with nix [here](./doc/nix.md).

### Arch (Linux)

`babashka` is [available](https://aur.archlinux.org/packages/babashka-bin/) in the [Arch User Repository](https://aur.archlinux.org). It can be installed using your favorite [AUR](https://aur.archlinux.org) helper such as
[yay](https://github.com/Jguer/yay), [yaourt](https://github.com/archlinuxfr/yaourt), [apacman](https://github.com/oshazard/apacman) and [pacaur](https://github.com/rmarquis/pacaur). Here is an example using `yay`:

    yay -S babashka-bin

### asdf

[asdf](https://github.com/asdf-vm/asdf) is an extendable version manager for linux and macOS.

Babashka can be installed using a plugin as follows:

    asdf plugin add babashka
    asdf install babashka latest

### Windows

#### Scoop

On Windows you can install using [scoop](https://scoop.sh/) and the
[scoop-clojure](https://github.com/littleli/scoop-clojure) bucket.

Or just follow these concrete steps:
``` powershell
# Note: if you get an error you might need to change the execution policy (i.e. enable Powershell) with
# Set-ExecutionPolicy RemoteSigned -scope CurrentUser
Invoke-Expression (New-Object System.Net.WebClient).DownloadString('https://get.scoop.sh')

scoop bucket add scoop-clojure https://github.com/littleli/scoop-clojure
scoop bucket add extras
scoop install babashka
```

#### Manual

If scoop does not work for you, then you can also just download the `bb.exe`
binary from [Github releases](https://github.com/babashka/babashka/releases) and
place it on your path manually.

### Installer script

Install via the installer script:

``` shell
$ curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
$ chmod +x install
$ ./install
```

By default this will install into `/usr/local/bin` (you may need `sudo` for
this). To change this, provide the directory name:

``` shell
$ ./install --dir .
```

To install a specific version, the script also supports `--version`:

``` shell
$ ./install --dir . --version 0.4.1
```

To force the download of the zip archive to a different directory than `/tmp`
use the `--download-dir` argument:

``` shell
$ ./install --dir . --version 0.4.1 --download-dir .
```

On Linux, if you want to install the static binary version:

``` shell
$ ./install --dir . --version 0.4.1 --download-dir . --static
```

In case you want to check the download, you can use the `--checksum` option.
This maybe useful for unattended installations:

``` shell
$ sha256sum babashka-0.4.1-linux-amd64-static.tar.gz
ab70fb39fdbb5206c0a2faab178ffb54dd9597991a4bc13c65df2564e8f174f6  babashka-0.4.1-linux-amd64-static.tar.g
$ ./install --dir /tmp --checksum ab70fb39fdbb5206c0a2faab178ffb54dd9597991a4bc13c65df2564e8f174f6 --static --version 0.4.1
```

Note that the `--checksum` option only works when `--version` option is also
provided. This is to avoid breakage when a new version of Babashka is released.

### Github releases

You may also download a binary from
[Github](https://github.com/babashka/babashka/releases). For linux there is a
static binary available which can be used on Alpine.

## Docker

Check out the image on [Docker hub](https://hub.docker.com/r/babashka/babashka/).

## [News](doc/news.md)

Check out the [news](doc/news.md) page to keep track of babashka-related news items.

## [Built-in namespaces](https://book.babashka.org/#built-in-namespaces)

Go [here](https://book.babashka.org/#built-in-namespaces) to see the full list of built-in namespaces.

## [Projects](doc/projects.md)

A list of projects (scripts, libraries, pods and tools) known to work with babashka.

### Flair

Do you have a library that runs with babashka? Add this badge to add some flair
to your repo:

[![bb compatible](/logo/badge.svg)](https://babashka.org)

The raw HTML:

``` html
<a href="https://babashka.org" rel="nofollow"><img src="https://github.com/babashka/babashka/raw/master/logo/badge.svg" alt="bb compatible" style="max-width: 100%;"></a>
```

## [Pods](https://github.com/babashka/babashka.pods)

Pods are programs that can be used as a Clojure library by
babashka. Documentation is available in the [pod library
repo](https://github.com/babashka/pods).

A list of available pods can be found [here](doc/projects.md#pods).

## Differences with Clojure

Babashka is implemented using the [Small Clojure
Interpreter](https://github.com/borkdude/sci). This means that a snippet or
script is not compiled to JVM bytecode, but executed form by form by a runtime
which implements a substantial subset of Clojure. Babashka is compiled to
a native binary using [GraalVM](https://github.com/oracle/graal). It comes with
a selection of built-in namespaces and functions from Clojure and other useful
libraries. The data types (numbers, strings, persistent collections) are the
same. Multi-threading is supported (`pmap`, `future`).

Differences with Clojure:

- A pre-selected set of Java classes are supported. You cannot add Java classes
  at runtime.

- Interpretation comes with overhead. Therefore loops are slower than in Clojure
  on the JVM. In general interpretation yields slower programs than compiled
  programs.

- No `deftype`, `definterface` and unboxed math.

- `defprotocol` and `defrecord` are implemented using multimethods and regular
  maps. Ostensibly they work the same, but under the hood there are no Java
  classes that correspond to them.

- Currently `reify` works only for one class at a time

- The `clojure.core.async/go` macro is not (yet) supported. For compatibility it
  currently maps to `clojure.core.async/thread`. More info
  [here](https://book.babashka.org/#core_async).

## Package babashka script as a AWS Lambda

AWS Lambda runtime doesn't support signals, therefore babashka has to disable
handling of SIGINT and SIGPIPE. This can be done by setting
`BABASHKA_DISABLE_SIGNAL_HANDLERS` to `true`.

## Articles, podcasts and videos

- [Recursive document transformations with Pandoc and Clojure](https://play.teod.eu/document-transform-pandoc-clojure/) by Teodor Heggelund
- [Blambda!](https://jmglov.net/blog/2022-07-03-blambda.html) by Josh Glover
- [Babashka CLI](https://blog.michielborkent.nl/babashka-cli.html): turn Clojure functions into CLIs!
- [Breakneck Babashka on K8s](Breakneck Babashka on K8s) by Heow Goodman
- [Recursive document transformations with Pandoc and Clojure](https://play.teod.eu/document-transform-pandoc-clojure/)
- [Detecting inconsistent aliases in a clojure codebase](https://www.youtube.com/watch?v=bf8KLKkCH2g) by Oxalorg
- [I, too, Wrote Myself a Static Site Generator](https://dawranliou.com/blog/i-too-wrote-myself-a-static-site-generator/) by Daw-Ran Liou
- [Babashka and Clojure](https://youtu.be/ZvOs5Ele6VE) by Rahul Dé at North Virginia Linux Users Group
- [Create a password manager with Clojure using Babashka, sqlite, honeysql and stash](https://youtu.be/jm0RXmyjRJ8) by Daniel Amber
- [Writing Clojure-living-cookbooks](https://www.loop-code-recur.io/live-clojure-cookbooks) by Cyprien Pannier
- [Using babashka with PHP](https://blog.michielborkent.nl/using-babashka-with-php.html) by Michiel Borkent
- [Moldable Emacs: a Clojure Playground with Babashka](https://ag91.github.io/blog/2021/11/05/moldable-emacs-a-clojure-playground-with-babashka/) by Andrea
- [Finding my inner Wes Anderson with #Babashka](https://javahippie.net/clojure/2021/10/18/finding-my-inner-wes-anderson.html) by Tim Zöller
- [Awesome Babashka: Parse & produce HTML and SQLite](https://blog.jakubholy.net/2021/awesome-babashka-dash/) by Jakub Holý
- [Babashka tasks](https://youtu.be/u5ECoR7KT1Y), talk by Michiel Borkent
- [Rewriting a clojure file with rewrite-clj and babashka](https://youtu.be/b7NPKsm8gkc), video by Oxalorg
- [Integrating Babashka into Bazel](https://timjaeger.io/20210627-integrating-babashka-with-bazel.html) by Tim Jäger
- [Talk](https://youtu.be/Yjeh57eE9rg): Babashka: a native Clojure interpreter for scripting — The 2021 Graal Workshop at CGO
- [Blog](https://savo.rocks/posts/playing-new-music-on-old-car-stereo-with-clojure-and-babashka/): Playing New Music On Old Car Stereo With Clojure And Babashka
- [Homoiconicity and feature flags](https://martinklepsch.org/posts/homoiconicity-and-feature-flags.html) by Martin Klepsch
- [Clojure like its PHP](https://eccentric-j.com/blog/clojure-like-its-php.html) by Jay Zawrotny (eccentric-j)
- [Deploy babashka script to AWS Lambda](https://www.jocas.lt/blog/post/babashka-aws-lambda/) by Dainius Jocas.
- [Automating Video Edits with Clojure and ffmpeg](https://youtu.be/Tmgy57R9HZM) by Adam James.
- [Exporter for passwordstore.org](https://www.ieugen.ro/posts/2020/2020-12-26-export-passwords-with-babashka/) by Eugen Stan
- [Babashka and sci internals](https://youtu.be/pgNp4Lk3gf0), a talk by Michiel Borkent at the [London Clojurians Meetup](https://www.meetup.com/London-Clojurians).
- [Writing Clojure on the Command Line with Babashka](https://youtu.be/RogyxI-GaGQ), a talk by Nate Jones.
- [Using Clojure in Command Line with Babashka](http://www.karimarttila.fi/clojure/2020/09/01/using-clojure-in-command-line-with-babashka.html), a blog article by Kari Marttila.
- [Babashka and GraalVM; taking Clojure to new places](https://youtu.be/3EUMA6bd-xQ), a talk by Michiel Borkent at [Clojure/NYC](https://www.meetup.com/Clojure-NYC/).
- [Import a CSV into Kafka, using Babashka](https://blog.davemartin.me/posts/import-a-csv-into-kafka-using-babashka/) by Dave Martin
- [Learning about babashka](https://amontalenti.com/2020/07/11/babashka), a blog article by Andrew Montalenti
- [Babashka Pods](https://www.youtube.com/watch?v=3Q4GUiUIrzg&feature=emb_logo) presentation by Michiel Borkent at the [Dutch Clojure Meetup](http://meetup.com/The-Dutch-Clojure-Meetup).
- [AWS Logs using Babashka](https://tech.toyokumo.co.jp/entry/aws_logs_babashka), a blog published by [Toyokumo](https://toyokumo.co.jp/).
- [The REPL podcast](https://www.therepl.net/episodes/36/) Michiel Borkent talks about [clj-kondo](https://github.com/borkdude/clj-kondo), [Jet](https://github.com/borkdude/jet), Babashka, and [GraalVM](https://github.com/oracle/graal) with Daniel Compton.
- [Implementing an nREPL server for babashka](https://youtu.be/0YmZYnwyHHc): impromptu presentation by Michiel Borkent at the online [Dutch Clojure Meetup](http://meetup.com/The-Dutch-Clojure-Meetup)
- [ClojureScript podcast](https://soundcloud.com/user-959992602/s3-e5-babashka-with-michiel-borkent) with Jacek Schae interviewing Michiel Borkent
- [Babashka talk at ClojureD](https://www.youtube.com/watch?v=Nw8aN-nrdEk) ([slides](https://speakerdeck.com/babashka/babashka-and-the-small-clojure-interpreter-at-clojured-2020)) by Michiel Borkent
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
- The library cannot be implemented as a
  [pod](https://github.com/babashka/babashka.pods).

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
commit](https://github.com/babashka/babashka/commit/02c7c51ad4b2b1ab9aa95c26a74448b138fe6659)).

## Related projects

- [planck](https://planck-repl.org/)
- [joker](https://github.com/candid82/joker)
- [closh](https://github.com/dundalek/closh)
- [lumo](https://github.com/anmonteiro/lumo)

## Contributors

Thanks to all the people that contributed to babashka:

- [Adgoji](https://www.adgoji.com/) for financial support
- [CircleCI](https://circleci.com/) for CI and additional support
- [Nikita Prokopov](https://github.com/tonsky) for the logo
- [Contributors](https://github.com/babashka/babashka/graphs/contributors) and
  other users posting issues with bug reports and ideas
- [Github sponsors](https://github.com/sponsors/borkdude)
- [OpenCollective sponsors](https://opencollective.com/babashka)
- [Clojurists Together](https://www.clojuriststogether.org/)

### Code Contributors

This project exists thanks to all the people who contribute. [[Contribute](doc/dev.md)].
<a href="https://github.com/babashka/babashka/graphs/contributors"><img src="https://opencollective.com/babashka/contributors.svg?width=890&button=false" /></a>

### Financial Contributors

#### Github Sponsors

- [Dig Gashinsky](https://github.com/digash)

#### OpenCollective
Become a financial contributor and help us sustain our community. [[Contribute](https://opencollective.com/babashka/contribute)]

##### Individuals

<a href="https://opencollective.com/babashka"><img src="https://opencollective.com/babashka/individuals.svg?width=890"></a>

##### Organizations

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
