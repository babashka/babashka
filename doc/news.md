# News

This page keeps track of babashka-related new items. Feel free to make a PR if
you have anything to add. Also see
[#babashka](https://twitter.com/hashtag/babashka?src=hashtag_click&f=live) on
Twitter.

## 2021-12

- Releases: [0.6.8 - 0.7.3](https://github.com/babashka/babashka/blob/master/CHANGELOG.md).
- [Scripting with Babashka on Alfred](https://blog.wsscode.com/babashka-and-alfred/) by Wilker Lucio
- [babashka/spec.alpha](https://github.com/babashka/spec.alpha): a fork of spec.alpha that works with babashka.
- Several people are doing [Advent of Code puzzles with babashka](https://twitter.com/search?q=%23babashka%20%20%23AdventOfCode&src=typed_query&f=live)
- Compatibility with a [fork of tools.namespace](https://github.com/babashka/tools.namespace). This allows
  running the Cognitect [test-runner](https://github.com/cognitect-labs/test-runner) (Cognitest) from source.
- [bb-components](https://github.com/vedang/bb-scripts#bb-components): A script to find all the components that you should deploy your code to.
- [Babashka workshop at JavaLand](https://github.com/ijug-ev/JavaLand/tree/main/Community-Aktivit%C3%A4ten#bash-war-gestern-shell-scripting-mit-babashka-clojure-ug-d%C3%BCsseldorf-christian-meter-rheinjug-jens-bendisposto)
- Install babashka [dev builds](https://twitter.com/borkdude/status/1475234968146227203)
- [Combine babashka and PHP](https://gist.github.com/borkdude/843548cba14ae9d283191e31bc483959)
- [System wide babashka tasks](https://twitter.com/borkdude/status/1476656022282551300)
- [Run an http file server as a babashka task](https://twitter.com/borkdude/status/1476870516233445377)

## 2021-11

- Releases: [0.6.5 - 0.6.7](https://github.com/babashka/babashka/blob/master/CHANGELOG.md).
- [Contajners](https://github.com/lispyclouds/contajners): An idiomatic,
  data-driven, REPL friendly clojure client for OCI container engines.
- [deps-modules](https://github.com/exoscale/deps-modules#babashka):  a clojure "tool" that attempts to solve one of the "multi module" project problems with tools.deps in a minimalistic way.
- [Moldable Emacs: a Clojure Playground with Babashka](https://ag91.github.io/blog/2021/11/05/moldable-emacs-a-clojure-playground-with-babashka/)
- [aws pod 0.1.0](https://twitter.com/borkdude/status/1459117378441261056)
- [tools.bbuild](https://github.com/babashka/tools.bbuild): a fork of tools.build that runs with babashka and [tools-deps-native](https://github.com/babashka/tools-deps-native)
- [Making markdown-clj babashka compatible](https://blog.michielborkent.nl/markdown-clj-babashka-compatible.html) by Michiel Borkent
- [radiale](https://github.com/xlfe/radiale): radiale: home automation project written using #babashka and Python
- [Writing a Clojure shell script with Babashka](https://www.youtube.com/watch?v=D-_Mz7rz1po), a video by Max Weber
- [makejack](https://github.com/hugoduncan/makejack): A clojure CLI build tool, and build library which can run with babashka.
- [I wrote myself a static site generator](https://freeston.me/posts/2021-11-29-new-site-generator/) (in babashka) by Dominic Freeston.
- [bipe](https://gist.github.com/borkdude/82dcdd36a1e61ef36f19221876e7b1b6): vipe for babashka


## 2021-10
- Releases: [0.6.2-0.6.4](https://github.com/babashka/babashka/blob/master/CHANGELOG.md).
- Babashka on the [Thoughtworks Technology Radar](https://www.thoughtworks.com/radar/platforms/babashka)
- [ruuter](https://github.com/askonomm/ruuter#setting-up-with-babashka) is a
  routing library compatible with babashka.
- A list of [companies](https://github.com/babashka/babashka/blob/master/doc/companies.md) using babashka
- Ilshat Sultanov shares his [babashka tasks](https://twitter.com/just_sultanov/status/1446118161258987534)
- [Slack the music](https://github.com/javahippie/slack-the-music), a babashka script to update your slack status with your current Apple Music track by Tim Zöller.
- [Finding my inner Wes Anderson](https://javahippie.net/clojure/2021/10/18/finding-my-inner-wes-anderson.html) by Tim Zöller.
- [Run a local babashka script in a remote server](https://twitter.com/borkdude/status/1451110414062870528)
- [Replacing my Octopress blog with 200 lines of Babashka](https://blog.michielborkent.nl/migrating-octopress-to-babashka.html) by Michiel Borkent
- The babashka wiki now has a [GNU Emacs](https://github.com/babashka/babashka/wiki/GNU-Emacs) section
- Invoke babashka tasks in a [monorepo](https://github.com/babashka/babashka/discussions/1044#discussioncomment-1544956)
- [Speeding up builds with fs/modified-since](https://blog.michielborkent.nl/speeding-up-builds-fs-modified-since.html) by Michiel Borkent

## 2021-09

- New babashka 0.6.0 released. Highlight: support for `java.net.http` which
  enables running [java-http-clj](https://github.com/schmee/java-http-clj) from
  source. The raw interop is the first part of a multi-stage plan to move all
  http related scripting towards `java.net.http` in favor of the other two
  solutions currently available in `bb`: `babashka.curl` and
  `org.httpkit.client`.
- [rss-saver](https://github.com/adam-james-v/rss-saver): Simple Clojure (Babashka) script that saves articles from world.hey.com RSS feeds.
- [babashka-docker-action-example](https://github.com/borkdude/babashka-docker-action-example)
- [script](https://gist.github.com/rutenkolk/dbd970d03a0d012b671db38434ccbfa7) to upgrade zig lang to the latest dev release
- [Cursive](https://twitter.com/CursiveIDE/status/1439022267187433472) adds support for babashka in 1.11.0
- How to filter tail output with babashka on [StackOverflow](https://stackoverflow.com/questions/69241046/how-to-filter-output-of-tail-with-babashka/69241911?stw=2#69241911)
- [Awesome Babashka: Parse & produce HTML and SQLite](https://blog.jakubholy.net/2021/awesome-babashka-dash/) by Jakub Holy
- [neil](https://github.com/babashka/neil): an installable babashka script to add common aliases and features to deps.edn-based projects.

## 2021-08

- New babashka 0.5.1 released. Highlight: new `print-deps` command for printing
  a `deps.edn` map and classpath which includes all built-in deps. This can be
  used for editor tooling like Cursive and clojure-lsp, but also for migrating a
  babashka project to a Graal native-image project.
- Babashka receives long term funding from [Clojurists Together](https://www.clojuriststogether.org/news/long-term-funding-selections/). Thanks!
- Babashka is 2 years old this month: the [first commit](https://github.com/babashka/babashka/commit/6dee50b0face0b890a7f628a63b21d5d706a48ee) was on 9 August 2019!
- [nbb](https://github.com/borkdude/nbb): babashka's Node.js cousin
- [Cursive](https://twitter.com/CursiveIDE/status/1422540023207915520) adds
  support for babashka in the latest EAP
- [clojure-lsp](https://twitter.com/ericdallo/status/1422559744712138753) adds support for babashka scripts
- [bb guestbook](https://github.com/SVMBrown/bb-guestbook) by Scot Brown.
- [bb htmx todo app](https://github.com/prestancedesign/babashka-htmx-todoapp) by Michael Sahili.
- [bb aws lambda runtime](https://github.com/tatut/bb-lambda) by Tatu Tarvainen.

## 2021-07

- New babashka 0.5.0 released. Highlights: `clojure.tools.logging` + `taoensso.timbre` added, source compatibility with `clojure.data.json`.
- [Babashka tasks](https://youtu.be/u5ECoR7KT1Y), talk by Michiel Borkent
- [Rewriting a clojure file with rewrite-clj and babashka](https://youtu.be/b7NPKsm8gkc), video by Oxalorg
- [Babashka tasks for PostgreSQL](https://github.com/babashka/babashka/discussions/929) by Stel Abrego
- [String interpolation](https://twitter.com/yogthos/status/1415324124361154561) with Selmer in bb
- [Jirazzz](https://github.com/rwstauner/jirazzz): a babashka JIRA client by Randy Stauner
- [Babashka + scittle guestbook](https://github.com/kloimhardt/babashka-scittle-guestbook) by Kloimhardt
- [Paillier cryptosystem](https://github.com/babashka/babashka/discussions/948) by litteli
- [csv-to-yaml.clj](https://github.com/babashka/babashka/discussions/939) by Cora

## 2021-06

- New babashka 0.4.4 - 0.4.5 released.
- Share your babashka creations on the [Show and tell](https://github.com/babashka/babashka/discussions/categories/show-and-tell) forum on Github.
- [Integrating Babashka into Bazel](https://timjaeger.io/20210627-integrating-babashka-with-bazel.html) by Tim Jäger
- [Babashka + scittle guestbook example](https://github.com/kloimhardt/babashka-scittle-guestbook)
- [Slingshot works with babashka](https://twitter.com/borkdude/status/1402547783295504387)
- [Spire gets a babashka pod](https://twitter.com/epic_castle/status/1402212817533431808)
- [Text to speech AWS example](https://twitter.com/FieryCodDev/status/1401843357555511301) with scittle and babashka.
- [Game of Life](https://gist.github.com/mmzsource/655b9dcfe56eed8a045022837186ed84)
- [ob-babashka](https://gist.github.com/adam-james-v/f4d2b75a70b095d14a351a1eff96b4b0): Emacs org-babel functions for babashka.
- [Normalize auto-resolved keywords](https://github.com/babashka/babashka/tree/master/examples#normalize-keywordsclj)
- [Create PostgreSQL backups](https://twitter.com/stelstuff/status/1400559261025980418) using babashka.
- [Change flutter SDK](https://gist.github.com/ampersanda/aac70cc0644df12199ea32988f3c4d73) using babashka.

## 2021-05

- Babashka 0.3.7 - 0.4.3 released. Highlights:
    - New [task runner feature](https://book.babashka.org/#tasks).
    - Add [Selmer](https://github.com/yogthos/Selmer) to built-in libraries.
    - Add compatibility with [jasentaa](https://github.com/rm-hull/jasentaa).
- New [website](https://babashka.org).
- [Talk](https://youtu.be/Yjeh57eE9rg): Babashka: a native Clojure interpreter for scripting — The 2021 Graal Workshop at CGO
- [Blog](https://savo.rocks/posts/playing-new-music-on-old-car-stereo-with-clojure-and-babashka/): Playing New Music On Old Car Stereo With Clojure And Babashka
- [Homoiconicity and feature flags](https://martinklepsch.org/posts/homoiconicity-and-feature-flags.html) by Martin Klepsch.
- [Manage your macOS setup](https://github.com/cldwalker/osx-setup) using babashka.
- [Localizing a Ghost theme](https://martinklepsch.org/posts/localizing-a-ghost-theme.html) by Martin Klepsch.
- [Babashka SQL pods 0.0.8](https://twitter.com/borkdude/status/1396136828479188997) including a MySQL pod

## 2021-04

- Babashka 0.3.2 - 0.3.6 released. See [CHANGELOG.md](https://github.com/babashka/babashka/blob/master/CHANGELOG.md). Highlights:
     - Add [rewrite-clj](https://github.com/clj-commons/rewrite-clj).
     - Support for the [binf](https://github.com/helins/binf.cljc) library.
- [Sort requires and imports](https://gist.github.com/laurio/01530ea7700752885df21e92bb926f75) using rewrite-clj
- Babashka [tasks proposal](https://github.com/babashka/babashka/issues/778), available in babashka 0.3.5
- A new useful function in `babashka.fs`: [modified-since](https://babashka.org/fs/babashka.fs.html#var-modified-since)
- Using babashka [for animations](https://twitter.com/RustyVermeer/status/1385269161106972673)
- [rewrite-edn](https://github.com/borkdude/rewrite-edn) is now compatible with babashka

## 2021-03

- Babashka 0.3.0 - 0.3.1 released. See [CHANGELOG.md](https://github.com/babashka/babashka/blob/master/CHANGELOG.md). Highlights: Raspberry Pi support, bb.edn, more flexible main invocation.
- [Babashka shebang](https://github.com/borkdude/deps.clj/blob/master/deps.bat#L1-L7) for Windows .bat files
- [Datalevin](https://twitter.com/huahaiy/status/1371689142585753604) now works as a babashka pod
- [Babashka sql pods](https://github.com/babashka/babashka-sql-pods/blob/master/CHANGELOG.md) update
- [JPoint](https://jpoint.ru/en/2021/talks/3nr1czuok3dvtewtcdjalm/) is going to have a talk on babashka
- A `python -m http.server` [replacement in babashka](https://gist.github.com/holyjak/36c6284c047ffb7573e8a34399de27d8)
- A [PR](https://github.com/ring-clojure/ring-codec/issues/26) to make `ring-codec` compatible with babashka
- The [stuartsierra/component](https://github.com/stuartsierra/component) library [seems to work with babashka](https://github.com/babashka/babashka/issues/742)
- [pathom3](https://pathom3.wsscode.com/docs/tutorials/babashka/) works with babashka!
- [VPN Connect](https://tech.toryanderson.com/2021/03/06/re-writing-an-openconnect-vpn-connect-script-in-babashka/) script
- [Github code search](https://gist.github.com/ertugrulcetin/4f35557962fac3d159d8c931e94873e9) script

## 2021-02

- Babashka 0.2.9 - 0.2.12 released
- [babashka.fs](https://github.com/babashka/fs): utility library for dealing with files (based on java.nio). Bundled with bb 0.2.9.
- New [Youtube channel](https://www.youtube.com/channel/UCRCl_R1ihLJt7IOgICdb9Lw) with babashka related videos
- MS SQL support for the [babashka sql pods](https://github.com/babashka/babashka-sql-pods/)
- [Clojure like its PHP](https://eccentric-j.com/blog/clojure-like-its-php.html): run babashka scripts as CGI scripts
- [Automating Video Edits with Clojure and ffmpeg](https://youtu.be/Tmgy57R9HZM) by Adam James
- [Gaka](https://github.com/cdaddr/gaka), a CSS-generating library that works with babashka.
- [Deploy babashka script to AWS Lambda](https://www.jocas.lt/blog/post/babashka-aws-lambda/) by Dainius Jocas.
- [Elisp](https://gist.github.com/llacom/f391f41cbf4de91739b52bf8bb1a6d54) and cider commands to spawn a babashka repl and connect to it
- [klein](https://gist.github.com/borkdude/c34e8e44eb5b4a6ca735bf8a86ff64fa), a
lein imitation script built on deps.edn
- [failjure](https://github.com/adambard/failjure) works with babashka.
- A [script](https://gist.github.com/borkdude/58f099b2694d206e6eec18daedc5077b) to solve our mono-repo problem with deps.edn at work.
- [Single-script vega-lite plotter](https://gist.github.com/vdikan/6b6063d6e1b00a3cd79bc7b3ce3853d6/)
- [Find vars with the clj-kondo pod](https://gist.github.com/borkdude/841d85d5ad04c517337166b3928697bd). Also see [video](https://youtu.be/TvBmtGS0KJE).
- [Another setup babashka Github action](https://github.com/marketplace/actions/setup-babashka)
- [AWS Lambda + babashka + minimal container image](https://gist.github.com/lukaszkorecki/a1fe27bf08f9b98e9def9da4bcb3264e)
- [football script](https://gist.github.com/mmzsource/a732950aa43d19c5a9b63bbb7f20b7eb)
- [ffclj](https://github.com/luissantos/ffclj): Clojure ffmpeg wrapper
- [clj-lineart](https://github.com/eccentric-j/clj-lineart): Generative line art from a clojure-cgi script
- [bunpack](https://github.com/robertfw/bunpack): remembers how to unpack things, so you don't have to
- A script to download deps for [all `deps.edn` aliases](https://github.com/babashka/babashka/blob/master/examples/download-aliases.clj)

## 2021-01

- Babashka [0.2.8](https://github.com/babashka/babashka/blob/master/CHANGELOG.md#v028) released. This includes new libraries: hiccup, core.match and clojure.test.check.
- On 27th of February, Michiel (a.k.a. @borkdude) will do a talk about babashka at the [2021 GraalVM workshop](https://graalworkshop.github.io/2021/).
- First release of the [aws pod](https://github.com/babashka/pod-babashka-aws).
- A [script](https://gist.github.com/borkdude/ba372c8cee311e31020b04063d88e1be) to print API breakage warnings.
- A [script](https://gist.github.com/lgouger/2262e2d2503306f2595e48a7888f4e73) to lazily page through AWS results using the new [aws pod](https://github.com/babashka/pod-babashka-aws).
- [Environ](https://github.com/weavejester/environ) works with babashka.
- [Expound](https://github.com/bhb/expound) now works with [spartan.spec](https://github.com/borkdude/spartan.spec/blob/master/examples/expound.clj)
- A basic [logger](https://gist.github.com/borkdude/c97da85da67c7bcc5671765aef5a89ad) that works in babashka scripts
- A basic [router](https://gist.github.com/borkdude/1627f39d072ea05557a324faf5054cf3) based on core.match
- A minimal [Github GraphQL client](https://gist.github.com/lagenorhynque/c1419487965c0fa3cf34862852825483)
- New developments around babashka on [Raspberry Pi](https://github.com/babashka/babashka/issues/241#issuecomment-763976749)

## 2020-12

- A new babashka talk: [Babashka and sci
internals](https://youtu.be/pgNp4Lk3gf0). Also see
[slides](https://speakerdeck.com/babashka/babashka-and-sci-internals-at-london-clojurians-december-2020)
and [REPL
session](https://gist.github.com/borkdude/66a4d844668e12ae1a8277af10d6cc4b).
- Babashka 0.2.6 released. See [release
notes](https://github.com/babashka/babashka/blob/master/CHANGELOG.md#v026).
- Babashka 0.2.5 released. See [release
notes](https://github.com/babashka/babashka/blob/master/CHANGELOG.md#v025).
- First release of the [sqlite pod](https://github.com/babashka/pod-babashka-sqlite3)
- First release of the [buddy pod](https://github.com/babashka/pod-babashka-buddy)
- The data from the babashka survey is now available
[here](https://nl.surveymonkey.com/results/SM-8W8V36DZ7/). I have provided a
summary [here](surveys/2020-11.md).
- Blog article: [exporter for passwordstore.org](https://www.ieugen.ro/posts/2020/2020-12-26-export-passwords-with-babashka/) by Eugen Stan
- [weavejester/progrock](https://github.com/weavejester/progrock) is a babashka-compatible library
  for printing progress bars.
- A [maze animation](https://gist.github.com/mmzsource/e8c383f69244ebefde058004fee72a8a) babashka script by [mmz](https://gist.github.com/mmzsource)

## 2020-11

- Babashka [survey](https://nl.surveymonkey.com/r/H2HK3RC). Feedback will be used
for future development.
- Babashka 0.2.4 released. See [release
notes](https://github.com/babashka/babashka/blob/master/CHANGELOG.md#v024).
- [Gaiwan.co](https://github.com/lambdaisland/gaiwan_co#tech-stack) are building their static HTML with babashka and [bootleg](https://github.com/retrogradeorbit/bootleg#babashka-pod-usage).
- [sha-words](https://github.com/ordnungswidrig/sha-words): A clojure program to
  turn a sha hash into list of nouns in a predictable jar.
- [Stash](https://github.com/rorokimdim/stash): a CLI for encrypted text storage
  written in Haskell, accessible as pod from babashka and Python!
- NextJournal released a babashka [notebook environment](http://nextjournal.com/try/babashka?cm6=1).
- [Interdep](https://github.com/rejoice-cljc/interdep) manages interdependent
  dependencies using Clojure's tools.deps and babashka.
- LA Clojure Meetup [presentation](https://youtu.be/RogyxI-GaGQ) by Nate Jones. Recorded in April 2020.
- [Github action](https://github.com/turtlequeue/setup-babashka) for babashka by Nicolas Ha.
- Oracle DB [feature flag](https://github.com/babashka/babashka/blob/master/doc/build.md#feature-flags) by Jakub Holy added.
- Torrent viewer [gist](https://gist.github.com/zelark/49ffbc0cd701c9299e35421ac2e3d5ab) by Aleksandr Zhuravlёv.
- Clone all repositories from a Gitlab group:
  [gist](https://gist.github.com/MrGung/81bee21eb52cb9307f336705d5ab08ad) by
  Steffen Glückselig.
- [Matchete](https://github.com/xapix-io/matchete), a pattern matching library,
  works with babashka. See
  [example](https://github.com/babashka/babashka/issues/631).

## 2020-10

- Babashka 0.2.3 released. See [release
notes](https://github.com/babashka/babashka/blob/master/CHANGELOG.md#v023-2020-10-21).
- [Malcolm Sparks](https://twitter.com/malcolmsparks/status/1320274099952848896) posted a
  [script](https://gist.github.com/malcolmsparks/61418b6bbcd0962536add1ccb07033b5) that
  sorts his photo collection.
- [Image viewer](https://github.com/babashka/babashka/tree/master/examples#image-viewer) example
- SQL Server [pod](https://github.com/xledger/pod_sql_server) by Isak Sky
- [SSH Auth Github](https://github.com/nextjournal/ssh-auth-github) by
  NextJournal.
- [pod-tzzh-mail](https://github.com/tzzh/pod-tzzh-mail): a pod to send mail.
- NextJournal [replaces bash with a babashka script](https://twitter.com/kommen/status/1311574776834666496)

## 2020-09

- Babashka
[0.2.1](https://github.com/babashka/babashka/blob/master/CHANGELOG.md#v021-2020-09-25)
and 0.2.2 released.
- Code Quality report for Clojure projects in Gitlab using babashka and clj-kondo. See [gist](https://gist.github.com/hansbugge/4be701d771057e8ef6bbbb0912656355). By Hans Bugge.
- [pod-tzzh-aws](https://github.com/tzzh/pod-tzzh-aws): a pod to interact with AWS.
- [spotifyd-notification](https://github.com/dharrigan/spotifyd-notification) by
  David Harrigan.

## 2020-08

- Babashka [0.2.0](https://github.com/babashka/babashka/blob/master/CHANGELOG.md#v020-2020-08-28) released.
- Maarten Metz
  [blogs](https://www.mxmmz.nl/blog/building-a-website-with-babashka.html) about
  how he rebuilt his blog using babashka.

## 2020-07

- Blake Miller published [https://gitlab.com/blak3mill3r/emacs-ludicrous-speed](emacs-ludicrous-speed).
- [babashka-clojure](https://github.com/marketplace/actions/babashka-clojure) Github action.
- [testdoc](https://github.com/liquidz/testdoc) works with babashka.
- [babashka-test-action](https://github.com/liquidz/babashka-test-action)
- New release of [tabl](https://github.com/justone/tabl)
  which also can be used as a pod from babashka.

## 2020-06

- Babashka [0.1.3](https://github.com/babashka/babashka/blob/master/CHANGELOG.md#v013-2020-06-27) and 0.1.2 released.
- New release of [brisk](https://github.com/justone/brisk), a CLI around nippy which can be used as a pod from babashka.
- [passphrase.clj](https://gist.github.com/snorremd/43c49649d2d844ee1e646fee67c141bb) script by Snorre Magnus Davøen
