# Changelog

For a list of breaking changes, check [here](#breaking-changes).

## v0.2.8

### New

- Include [clojure.core.match](https://github.com/clojure/core.match) #594
- Include [hiccup](https://github.com/weavejester/hiccup) #646
- Include [clojure.test.check](https://github.com/clojure/test.check) #487. Included namespaces:
  - clojure.test.check
  - clojure.test.check.generators
  - clojure.test.check.properties

### Fixed enhanced

- Fix symbol resolution in syntax quote when overwriting core var
- Performance enhancements

## v0.2.7

### New

- Add Alpine [Docker images](https://hub.docker.com/repository/registry-1.docker.io/babashka/babashka/tags?page=1&ordering=last_updated) [#699](https://github.com/babashka/babashka/issues/699) ([@lispyclouds](https://github.com/lispyclouds))
- Add `pp` from `clojure.pprint` [#707](https://github.com/babashka/babashka/issues/707)

### Fixed / enhancd

- Fix issue with unzipping nested directory [babashka/pod-registry#4](https://github.com/babashka/pod-registry/issues/4)
- Test cannot be defined conditionally [#705](https://github.com/babashka/babashka/issues/705)
- Add `--download-dir` option to install script [#688](https://github.com/babashka/babashka/issues/688)
- `(instance? clojure.lang.Fn x)` now works
- (.keySet {:a 1}) returns nil [#711](https://github.com/babashka/babashka/issues/711)
- Various performance enhancements
- Babashka.curl: allow keywords as header names [#32](https://github.com/babashka/babashka.curl/pull/32) ([@xificurC](https://github.com/xificurC))

## v0.2.6

### New

- Implement [pod registry](https://github.com/babashka/pod-registry) to
  automatically obtain pods when used in
  script. [#690](https://github.com/babashka/babashka/issues/690)
- [Buddy pod](https://github.com/babashka/pod-babashka-buddy) [#656](https://github.com/babashka/babashka/issues/656)
- [Etaoin pod 0.0.1 release](https://github.com/babashka/pod-babashka-etaoin)
- [Filewatcher pod 0.0.1 release](https://github.com/babashka/pod-babashka-filewatcher)
- [Fswatcher pod](https://github.com/babashka/pod-babashka-fswatcher) ([@lispyclouds](https://github.com/lispyclouds))

### Fixed / enhanced

- Auto-resolved map fix [#684](https://github.com/babashka/babashka/issues/684)
- Handle whitespace after read-cond splice
- Several performance improvements

### Thanks

Special thanks to [@lispyclouds](https://github.com/lispyclouds) for hammocking
on the pod registry and working on the new fswatcher pod.

## v0.2.5

This release adds a new `babashka.deps` namespace which offers [tools.deps
integration](https://clojure.org/guides/deps_and_cli). See
[docs](https://book.babashka.org/#babashkadeps).

### New

- Add `get-classpath` and `split-classpath` to `babashka.classpath`
  namespace. [#670](https://github.com/babashka/babashka/issues/670). See
  [docs](https://book.babashka.org/#babashka_classpath).
- Expose `add-deps` in `babashka.deps`
  [#677](https://github.com/babashka/babashka/issues/677). See
  [docs](https://book.babashka.org/#_add_deps).
- Expose `clojure` in `babashka.deps`
  [#678](https://github.com/babashka/babashka/issues/678). See
  [docs](https://book.babashka.org/#_clojure).
- Implement `--clojure` option to invoke a JVM clojure process similar to the
  official Clojure CLI. See [docs](https://book.babashka.org/#_invoking_clojure).

### Fixed / enhanced

- Add syntax checks to `binding` macro [#666](https://github.com/babashka/babashka/issues/666)
- Upgrade to GraalVM 20.3.0 [#653](https://github.com/babashka/babashka/issues/653)

## v0.2.4

Thanks to [Nextjournal](https://nextjournal.com/) for funding work on
prepl. Thanks to the community for taking the time to create issues, discussions
and code contributions. Thanks to sponsors on
[OpenCollective](https://opencollective.com/babashka) and
[Github](https://github.com/sponsors/borkdude) for continued financial support.

### New

- pREPL implementation
  [#664](https://github.com/babashka/babashka/issues/664). See
  [docs](https://github.com/babashka/babashka/blob/master/doc/repl.md#prepl).
  The pREPL is used by NextJournal to expose a babashka [notebook
  environment](http://nextjournal.com/try/babashka?cm6=1).
- [News page](doc/news.md) where you can follow the latest developments around babashka.
- Expose `pprint/simple-dispatch` [#627](https://github.com/babashka/babashka/issues/627)
- Support nested libspecs [borkdude/sci#399](https://github.com/borkdude/sci/issues/399)
- Add OracleDB feature flag [#638](https://github.com/babashka/babashka/issues/638) ([@holyjak](https://github.com/holyjak))
- Docker build documentation improvements [#643](https://github.com/babashka/babashka/issues/643) ([@holyjak](https://github.com/holyjak))
- Implement `get-thread-bindings`, `var-get` and `var-set`
- Print used port when starting nREPL server ([@plexus](https://github.com/plexus))

### Fixed / enhanced

- Can't call symbol literal as function [#622](https://github.com/babashka/babashka/issues/622)
- `:or` in destructuring broken for `false` case
- Support aliases in protocol fns [borkdude/sci#440](https://github.com/borkdude/sci/issues/440)
- Reader metadata preservation and evaluation fixes [#654](https://github.com/babashka/babashka/issues/654), [borkdude/sci#447](https://github.com/borkdude/sci/issues/447), [borkdude/sci#448](https://github.com/borkdude/sci/issues/448)
- Optimization for constant colls [borkdude/sci#452](https://github.com/borkdude/sci/issues/452)
- `ns-unmap` doesn't work for imported classes [borkdude/sci#432](https://github.com/borkdude/sci/issues/432)
- Fix parsing of trailing uneval in reader conditional
  [borkdude/edamame#65](https://github.com/borkdude/edamame/issues/65)
- `symbol` works on sci var [borkdude/sci#453](https://github.com/borkdude/sci/issues/453)

### Changed

- Remove cheshire smile functions [#658](https://github.com/babashka/babashka/issues/658)
- `babashka.curl` now calls curl with `--compressed` by default [babashka/babashka.curl#28](https://github.com/babashka/babashka.curl)

## v0.2.3 (2020-10-21)

Thanks to [@tzzh](https://github.com/tzzh), [@Heliosmaster](https://github.com/Heliosmaster), [@lispyclouds](https://github.com/lispyclouds) and [@kwrooijen](https://github.com/kwrooijen) for contributing to this release. Thanks to [Clojurists Together](https://www.clojuriststogether.org/) for sponsoring this release. Thanks to [Adgoji](https://github.com/AdGoji) and other sponsors on [OpenCollective](https://opencollective.com/babashka) and [Github](https://github.com/sponsors/borkdude) for their ongoing support.

### New

- [babashka/process](https://github.com/babashka/process): a Clojure library for working with `java.lang.Process`
- [pod-tzzh-mail](https://github.com/tzzh/pod-tzzh-mail): a pod for sending mail by [@tzzh](https://github.com/tzzh)
- [pod-babashka-lanterna](https://github.com/babashka/pod-babashka-lanterna): a pod for creating TUI apps
- [pod.xledger.sql-server](https://github.com/xledger/pod_sql_server): a pod for interacting with SQL Server
- Add `lazy-cat` [#605](https://github.com/babashka/babashka/issues/605)
- Support error output in babashka.nrepl
  [babashka.nrepl#28](https://github.com/babashka/babashka.nrepl/issues/28)
  ([@tzzh](https://github.com/tzzh))
- Add lanterna [feature flag](https://github.com/babashka/babashka/commit/13f65f05aeff891678e88965d9fbd146bfa87f4e) ([@kwrooijen](https://github.com/kwrooijen))
- Add socket support to pods [babashka/pods#2](https://github.com/babashka/pods/issues/2)
- Add `curl` to babashka/babashka Docker image to support `babashka.curl` ([@hansbugge](https://github.com/hansbugge))
- Add `transit+json` format support to pods [babashka/pods#21](https://github.com/babashka/pods/issues/21)
- Add `bound?` [borkdude/sci#430](https://github.com/borkdude/sci/issues/430)
- Add [portal](https://github.com/babashka/babashka/tree/master/examples#portal) example
- Add `*print-namespace-maps*` [borkdude/sci#428](https://github.com/borkdude/sci/issues/428)
- Support `clojure.java.io/Coercions` protocol [#601](https://github.com/babashka/babashka/issues/601)
- Add `clojure.pprint/write` [#607](https://github.com/babashka/babashka/issues/607)
- Add pretty-printer vars from `cheshire.core` [#619](https://github.com/babashka/babashka/issues/619)

### Fixed

- `pprint/print-table` should write to `sci/out` [#611](https://github.com/babashka/babashka/issues/611)
- `System/exit` doesn't work in REPL [#605](https://github.com/babashka/babashka/issues/606)
- Fix pod destroy function [#615](https://github.com/babashka/babashka/issues/615)
- Bind `*file*` in nREPL server [babashka/babashka.nrepl#31](https://github.com/babashka/babashka.nrepl/issues/31)
- Support `map->` constructor on defrecords [borkdude/sci#431](https://github.com/borkdude/sci/issues/431)
- Import should return class [#610](https://github.com/babashka/babashka/issues/610)

### Changed

- The [Docker image](https://hub.docker.com/r/babashka/babashka/) is now based
  on Ubuntu instead of Alpine.

## v0.2.2 (2020-09-30)

This is a patch release for
[babashka/babashka.pods#20](https://github.com/babashka/babashka.pods/issues/20),
but it also introduces new support around `reify`.

### New

- Support `java.nio.file.FileVisitor` and `java.io.FilenameFilter` with `reify` [#600](https://github.com/babashka/babashka/issues/600). Nice side effect: this makes babashka compatible with the [fs](https://github.com/clj-commons/fs) library:
    ``` clojure
    $ export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {clj-commons/fs {:mvn/version "1.5.2"}}}')
    $ bb -e '(ns foo (:require [me.raynes.fs :as fs])) (map str (fs/glob "*.md"))'
    ("/Users/borkdude/Dropbox/dev/clojure/glam/README.md")
    ```
- Add classes `java.util.zip.ZipInputStream` and `java.util.zip.ZipEntry`. This makes babashka compatible with [glam](https://github.com/borkdude/glam), a work in progress package manager.

### Fixed

- Ensure ns map exists for namespaces used only "code" vars [babashka/babashka.pods#20](https://github.com/babashka/babashka.pods/issues/20). This fixes compatibility with [bootleg](https://github.com/retrogradeorbit/bootleg).

## v0.2.1 (2020-09-25)

Thanks to [@RickMoynihan](https://github.com/RickMoynihan), [@joinr](https://github.com/joinr), [@djblue](https://github.com/djblue), [@lread](https://github.com/lread), [@teodorlu](https://github.com/teodorlu), [@tzzh](https://github.com/tzzh) and [@zoren](https://github.com/zoren) for contributing to this release. Thanks to [Clojurists Together](https://www.clojuriststogether.org/) for sponsoring this release.

### New

- Include `org.httpkit.client`, a high performance async http client [#561](https://github.com/babashka/babashka/issues/561)
- Include `org.httpkit.server`, an HTTP server
  [#556](https://github.com/babashka/babashka/issues/556). This namespace should
  be considered experimental and may stay or be removed in a future version of
  babashka, depending on feedback from the community. See [example](examples/httpkit_server.clj)
- Add `java.io.FileNotFoundException`, `java.security.DigestInputStream`, `java.nio.file.FileVisitOption` classes
- Support implementing `IDeref`, `IAtom` and `IAtom2` on records [sci#401](https://github.com/borkdude/sci/issues/401)
- Support compatibility with [version-clj](https://github.com/xsc/version-clj) [#565](https://github.com/babashka/babashka/issues/565) [@lread](https://github.com/lread) and [@borkdude](https://github.com/borkdude)
- Support YAML roundtrip through `*input*` [#583](https://github.com/babashka/babashka/issues/583)
- Support `clojure.core/find-var` [sci#420](https://github.com/borkdude/sci/issues/420) [@RickMoynihan](https://github.com/RickMoynihan)
- Support `clojure.pprint/cl-format` [#571](https://github.com/babashka/babashka/issues/571)
- [AWS pod](https://github.com/tzzh/pod-tzzh-aws)

### Fixed / enhanced

- Fix location printing in REPL (`--repl`) [#598](https://github.com/babashka/babashka/issues/589)
- Babashka.curl sends form params incorrectly as multipart [babashka.curl#25](https://github.com/babashka/babashka.curl/issues/25)
- Update Windows build instructions [#574](https://github.com/babashka/babashka/issues/574)
- Set minimum macOS version in build explicitly [#588](https://github.com/babashka/babashka/pull/588)
- Fix NPE in error handling logic [#587](https://github.com/babashka/babashka/issues/587)
- Fix namespace switch in REPL (`--repl`) [#564](https://github.com/babashka/babashka/issues/564)
- Fix location of errors in REPL (`--repl`) [#589](https://github.com/babashka/babashka/issues/589)
- Support multi-arity methods in `defprotocol` [sci#406](https://github.com/borkdude/sci/issues/406)
- Constructor call not recognized in protocol impl [sci#419](https://github.com/borkdude/sci/issues/419)
- Improve handling of top-level do in macro expansion [sci#421](https://github.com/borkdude/sci/issues/421)
- Performance improvements suggested by [@joinr](https://github.com/joinr) [sci#415](https://github.com/borkdude/sci/issues/415)
- Throw when trying to redefine referred var [sci#398](https://github.com/borkdude/sci/issues/398)
- `pprint` is now backed by `clojure.pprint/pprint` instead of fipp [#571](https://github.com/babashka/babashka/issues/571)

## v0.2.0 (2020-08-28)

Thanks to [@cldwalker](https://github.com/cldwalker), [@dehli](https://github.com/dehli), [@djblue](https://github.com/djblue), [@GomoSDG](https://github.com/GomoSDG), [@grahamcarlyle](https://github.com/grahamcarlyle), [@j-cr](https://github.com/j-cr),
[@jeroenvandijk](https://github.com/jeroenvandijk), [@justone](https://github.com/justone), [@kwrooijen](https://github.com/kwrooijen), [@lread](https://github.com/lread), [@patrick-galvin](https://github.com/patrick-galvin) and [@wodin](https://github.com/wodin) for
contributing to this release. Thanks to [Clojurists Together](https://www.clojuriststogether.org/) for sponsoring this release.

### New

- Add support for `clojure.datafy`, `Datafiable` and `Navigable` [#468](https://github.com/babashka/babashka/issues/468). To play with the new `clojure.datafy` support, you can use [portal](https://github.com/djblue/portal):
  ``` clojure
  $ bb -cp `clj -Spath -Sdeps '{:deps {djblue/portal {:mvn/version "0.4.0"}}}'`
  ```
- Add support for building and running uberjars [#536](https://github.com/babashka/babashka/issues/536). See [docs](https://github.com/babashka/babashka#uberjar).
- Print context, locals and stack trace on exception [#543](https://github.com/babashka/babashka/issues/543).
- Expose more transit vars [#525](https://github.com/babashka/babashka/issues/525) ([@djblue](https://github.com/djblue))
- Add `add-tap`,`tap>`, `remove-tap`, `class?`, `iterator-seq`, `remove-watch`, `realized?`
- Add `clojure.walk/macroexpand-all`
- Add `java.lang.ProcessHandle` and better support for killing subprocesses via
  Java interop. See [test script](https://github.com/babashka/babashka/blob/7049b1b0bd582b717094703bcf299fb6363bb142/test/babashka/scripts/kill_child_processes.bb).
- Add `clojure.lang.ArityException` and tests to support the [circleci/bond](https://github.com/circleci/bond) library [#524](https://github.com/babashka/babashka/issues/524) ([@cldwalker](https://github.com/cldwalker)).
- Add `java.time.format.DateTimeParseException`

### Fixed

- Fix order of namespaces in uberscript [#535](https://github.com/babashka/babashka/issues/535)
- Fix reading resources from jar files [#528](https://github.com/babashka/babashka/issues/528)
- Switch from canonical to absolute paths in `:file` field on var metadata
  [#532](https://github.com/babashka/babashka/issues/532)
- Babashka shows wrong filename when error is from required ns [#508](https://github.com/babashka/babashka/issues/508)
- Eval metadata on var created with `defn` [borkdude/sci#36](https://github.com/borkdude/sci/issues/36)
- Metadata fn on var fails if calling the var itself [borkdude/sci#363](https://github.com/borkdude/sci/issues/363)
- Allow re-binding of core vars in with-redefs [borkdude/sci#375](https://github.com/borkdude/sci/issues/375)
- Fix `false` dynamic binding value (which was read as `nil`) [borkdude/sci#379](https://github.com/borkdude/sci/issues/379)
- Fix setting of `*warn-on-reflection*` in nREPL session [babashka/babashka.nrepl#25](https://github.com/babashka/babashka.nrepl/issues/25)
- Fix protocols with multiple methods on defrecords [borkdude/sci#367](https://github.com/borkdude/sci/issues/367) ([@patrick-galvin](https://github.com/patrick-galvin))

## v0.1.3 (2020-06-27)

Thanks [@llacom](https://github.com/llacom), [@AndreTheHunter](https://github.com/AndreTheHunter)and [@xingzheone](https://github.com/xingzheone) for contributing to this release.

### New

- Add eldoc support in babashka.nrepl ([@borkdude](https://github.com/borkdude) and [@llacom](https://github.com/llacom))
- Add `java.time.temporal.{TemportalAdjuster, TemporalAmount}` classes
- Add `clojure.java.browse/browse-url` [#495](https://github.com/babashka/babashka/issues/495)
- Add classes for cli-matic library ([@AndreTheHunter](https://github.com/AndreTheHunter))
- Add `babashka.version` system property [#479](https://github.com/babashka/babashka/issues/479)
- Add `java.net.ConnectException` class
- Add `babashka.file` system property to support `__name__ = "__main__"` pattern (see [docs](https://github.com/babashka/babashka#__name__--__main__-pattern)) [#478](https://github.com/babashka/babashka/issues/478).

### Fixed

- Make `clojure.test/report` a dynamic var [#482](https://github.com/babashka/babashka/issues/482), [#491](https://github.com/babashka/babashka/issues/491)
- Make `clojure.test/test-var` a dynamic var
- Allow arbitrary Clojure code in tagged literals (previously only EDN was allowed)
- Fix http-server example ([@xingzheone](https://github.com/xingzheone))
- Fix bug in `alter-var-root`: it used thread-local binding in updating root value
- Fix for invoking `bb -f file.clj` when `file.clj` was empty

## v0.1.2 (2020-06-14)

Thanks [@jeroenvandijk](https://github.com/jeroenvandijk) for contributing to this release.

- Support `:extend-via-metadata` option in protocols
- Fix classpath issue for Windows [#496](https://github.com/babashka/babashka/issues/496)
- Add `double-array`, `short-array` and `clojure.lang.BigInt` for compatibility with
  [clojure.data.generators](https://github.com/clojure/data.generators)
- Add support for `*print-level*`
- Add version info in `:describe` message of babashka.nrepl [#471](https://github.com/babashka/babashka/issues/471)
- Add compatibility for [honeysql](https://github.com/seancorfield/honeysql)
  (most notable change: support `import` for records)

## v0.1.1 (2020-06-10)

Thanks [@Chowlz](https://github.com/Chowlz) and
[@mharju](https://github.com/mharju) for contributing to this release.

This release brings compatibility with the
[camel-snake-kebab](https://github.com/clj-commons/camel-snake-kebab) and
[aero](https://github.com/juxt/aero/) libraries due to the introduction of
`defprotocol`, `defrecord` and other enhancements.

### New

- Add `java.io.Console`. This is useful for letting users type in passwords.
- Add initial support for `defprotocol` and `defrecord`
- Add `default-data-readers`

### Enhancements / fixes

- Fix interop with result of `.environment` method on `ProcessBuilder` [#460](https://github.com/babashka/babashka/issues/460)
- Disable signal handlers via environment variable for AWS Lambda [#462](https://github.com/babashka/babashka/issues/462) ([@Chowlz](https://github.com/Chowlz)). See [README.md](https://github.com/babashka/babashka#package-babashka-script-as-a-aws-lambda).
- babashka.curl: fix double quote escaping issue on Windows
- Fix resolving var in syntax-quote from other namespace brought in via `:refer`
- `io/resource` should return `nil` for non-relative paths instead of throwing
- Fix field access interop when wrapped in parens: `(Integer/SIZE)`

## v0.1.0 (2020-06-01)

Thanks [@martinklepsch](https://github.com/martinklepsch) and [@cldwalker](https://github.com/cldwalker) for contributing to this release.

- Add more `java.time` classes. This makes babashka fully compatible with the
  [cljc.java-time](https://github.com/henryw374/cljc.java-time) library.
- Add `java.lang.Float` class
- Add `java.nio.file.PathMatcher` class. This allows one to implement a
  [glob](test-resources/babashka/glob.clj) function.
- Support alternative interop form: `(. Integer -SIZE) ;;=> 32`
- [#454](https://github.com/babashka/babashka/issues/454): syntax check on amount of arguments to `def`
- [#458](https://github.com/babashka/babashka/issues/458): add `clojure.data` namespace

## Prior to v0.1.0

Details about releases prior to v0.1.0 can be found
[here](https://github.com/babashka/babashka/releases).

## Breaking changes

### v0.2.4

- Remove cheshire smile functions [#658](https://github.com/babashka/babashka/issues/658)

### v0.2.3

- The [Docker image](https://hub.docker.com/r/babashka/babashka/) is now based on Ubuntu instead of Alpine.

### v0.0.90

- The `next.jdbc` namespace and PostgresQL driver, introduced in `v0.0.89`, are
  no longer part of the standardly distributed `bb` binary. This is now
  available behind a feature flag. See [feature flag
  documentation](https://github.com/babashka/babashka/blob/master/doc/build.md#feature-flags).
- [babashka/babashka.curl#16](https://github.com/babashka/babashka.curl/issues/16):
  Exceptional status codes or nonzero `curl` exit codes will throw exceptions by
  default. You can opt out with `:throw false`.

### v0.0.79
- [babashka.curl#9](https://github.com/babashka/babashka.curl/issues/9):
  Functions in `babashka.curl` like `get`, `post`, etc. now always return a map
  with `:status`, `:body`, and `:headers`.

### v0.0.71
- [#267](https://github.com/babashka/babashka/issues/267) Change behavior of
  reader conditionals: the `:clj` branch is taken when it occurs before a `:bb`
  branch.

### v0.0.44 - 0.0.45
- [#173](https://github.com/babashka/babashka/issues/173): Rename `*in*` to
  `*input*` (in the `user` namespace). The reason for this is that it shadowed
  `clojure.core/*in*` when used unqualified.

### v0.0.43
- [#160](https://github.com/babashka/babashka/issues/160): Add support for
  `java.lang.ProcessBuilder`. See docs. This replaces the `conch` namespace.
