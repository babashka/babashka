# Changelog

For a list of breaking changes, check [here](#breaking-changes)

## v0.2.2 (2020-09-30)

This is a patch release for
[babashka/babashka.pods#20](https://github.com/babashka/babashka.pods/issues/20),
but it also introduces new support around `reify`.

### New

- Support `java.nio.file.FileVisitor` and `java.io.FilenameFilter` with `reify` [#600](https://github.com/borkdude/babashka/issues/600). Nice side effect: this makes babashka compatible with the [fs](https://github.com/clj-commons/fs) library:
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

- Include `org.httpkit.client`, a high performance async http client [#561](https://github.com/borkdude/babashka/issues/561)
- Include `org.httpkit.server`, an HTTP server
  [#556](https://github.com/borkdude/babashka/issues/556). This namespace should
  be considered experimental and may stay or be removed in a future version of
  babashka, depending on feedback from the community. See [example](examples/httpkit_server.clj)
- Add `java.io.FileNotFoundException`, `java.security.DigestInputStream`, `java.nio.file.FileVisitOption` classes
- Support implementing `IDeref`, `IAtom` and `IAtom2` on records [sci#401](https://github.com/borkdude/sci/issues/401)
- Support compatibility with [version-clj](https://github.com/xsc/version-clj) [#565](https://github.com/borkdude/babashka/issues/565) [@lread](https://github.com/lread) and [@borkdude](https://github.com/borkdude)
- Support YAML roundtrip through `*input*` [#583](https://github.com/borkdude/babashka/issues/583)
- Support `clojure.core/find-var` [sci#420](https://github.com/borkdude/sci/issues/420) [@RickMoynihan](https://github.com/RickMoynihan)
- Support `clojure.pprint/cl-format` [#571](https://github.com/borkdude/babashka/issues/571)
- [AWS pod](https://github.com/tzzh/pod-tzzh-aws)

### Fixed / enhanced

- Fix location printing in REPL (`--repl`) [#598](https://github.com/borkdude/babashka/issues/589)
- Babashka.curl sends form params incorrectly as multipart [babashka.curl#25](https://github.com/borkdude/babashka.curl/issues/25)
- Update Windows build instructions [#574](https://github.com/borkdude/babashka/issues/574)
- Set minimum macOS version in build explicitly [#588](https://github.com/borkdude/babashka/pull/588)
- Fix NPE in error handling logic [#587](https://github.com/borkdude/babashka/issues/587)
- Fix namespace switch in REPL (`--repl`) [#564](https://github.com/borkdude/babashka/issues/564)
- Fix location of errors in REPL (`--repl`) [#589](https://github.com/borkdude/babashka/issues/589)
- Support multi-arity methods in `defprotocol` [sci#406](https://github.com/borkdude/sci/issues/406)
- Constructor call not recognized in protocol impl [sci#419](https://github.com/borkdude/sci/issues/419)
- Improve handling of top-level do in macro expansion [sci#421](https://github.com/borkdude/sci/issues/421)
- Performance improvements suggested by [@joinr](https://github.com/joinr) [sci#415](https://github.com/borkdude/sci/issues/415)
- Throw when trying to redefine referred var [sci#398](https://github.com/borkdude/sci/issues/398)
- `pprint` is now backed by `clojure.pprint/pprint` instead of fipp [#571](https://github.com/borkdude/babashka/issues/571)

## v0.2.0 (2020-08-28)

Thanks to [@cldwalker](https://github.com/cldwalker), [@dehli](https://github.com/dehli), [@djblue](https://github.com/djblue), [@GomoSDG](https://github.com/GomoSDG), [@grahamcarlyle](https://github.com/grahamcarlyle), [@j-cr](https://github.com/j-cr),
[@jeroenvandijk](https://github.com/jeroenvandijk), [@justone](https://github.com/justone), [@kwrooijen](https://github.com/kwrooijen), [@lread](https://github.com/lread), [@patrick-galvin](https://github.com/patrick-galvin) and [@wodin](https://github.com/wodin) for
contributing to this release. Thanks to [Clojurists Together](https://www.clojuriststogether.org/) for sponsoring this release.

### New

- Add support for `clojure.datafy`, `Datafiable` and `Navigable` [#468](https://github.com/borkdude/babashka/issues/468). To play with the new `clojure.datafy` support, you can use [portal](https://github.com/djblue/portal):
  ``` clojure
  $ bb -cp `clj -Spath -Sdeps '{:deps {djblue/portal {:mvn/version "0.4.0"}}}'`
  ```
- Add support for building and running uberjars [#536](https://github.com/borkdude/babashka/issues/536). See [docs](https://github.com/borkdude/babashka#uberjar).
- Print context, locals and stack trace on exception [#543](https://github.com/borkdude/babashka/issues/543).
- Expose more transit vars [#525](https://github.com/borkdude/babashka/issues/525) ([@djblue](https://github.com/djblue))
- Add `add-tap`,`tap>`, `remove-tap`, `class?`, `iterator-seq`, `remove-watch`, `realized?`
- Add `clojure.walk/macroexpand-all`
- Add `java.lang.ProcessHandle` and better support for killing subprocesses via
  Java interop. See [test script](https://github.com/borkdude/babashka/blob/7049b1b0bd582b717094703bcf299fb6363bb142/test/babashka/scripts/kill_child_processes.bb).
- Add `clojure.lang.ArityException` and tests to support the [circleci/bond](https://github.com/circleci/bond) library [#524](https://github.com/borkdude/babashka/issues/524) ([@cldwalker](https://github.com/cldwalker)).
- Add `java.time.format.DateTimeParseException`

### Fixed

- Fix order of namespaces in uberscript [#535](https://github.com/borkdude/babashka/issues/535)
- Fix reading resources from jar files [#528](https://github.com/borkdude/babashka/issues/528)
- Switch from canonical to absolute paths in `:file` field on var metadata
  [#532](https://github.com/borkdude/babashka/issues/532)
- Babashka shows wrong filename when error is from required ns [#508](https://github.com/borkdude/babashka/issues/508)
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
- Add `clojure.java.browse/browse-url` [#495](https://github.com/borkdude/babashka/issues/495)
- Add classes for cli-matic library ([@AndreTheHunter](https://github.com/AndreTheHunter))
- Add `babashka.version` system property [#479](https://github.com/borkdude/babashka/issues/479)
- Add `java.net.ConnectException` class
- Add `babashka.file` system property to support `__name__ = "__main__"` pattern (see [docs](https://github.com/borkdude/babashka#__name__--__main__-pattern)) [#478](https://github.com/borkdude/babashka/issues/478).

### Fixed

- Make `clojure.test/report` a dynamic var [#482](https://github.com/borkdude/babashka/issues/482), [#491](https://github.com/borkdude/babashka/issues/491)
- Make `clojure.test/test-var` a dynamic var
- Allow arbitrary Clojure code in tagged literals (previously only EDN was allowed)
- Fix http-server example ([@xingzheone](https://github.com/xingzheone))
- Fix bug in `alter-var-root`: it used thread-local binding in updating root value
- Fix for invoking `bb -f file.clj` when `file.clj` was empty

## v0.1.2 (2020-06-14)

Thanks [@jeroenvandijk](https://github.com/jeroenvandijk) for contributing to this release.

- Support `:extend-via-metadata` option in protocols
- Fix classpath issue for Windows [#496](https://github.com/borkdude/babashka/issues/496)
- Add `double-array`, `short-array` and `clojure.lang.BigInt` for compatibility with
  [clojure.data.generators](https://github.com/clojure/data.generators)
- Add support for `*print-level*`
- Add version info in `:describe` message of babashka.nrepl [#471](https://github.com/borkdude/babashka/issues/471)
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

- Fix interop with result of `.environment` method on `ProcessBuilder` [#460](https://github.com/borkdude/babashka/issues/460)
- Disable signal handlers via environment variable for AWS Lambda [#462](https://github.com/borkdude/babashka/issues/462) ([@Chowlz](https://github.com/Chowlz)). See [README.md](https://github.com/borkdude/babashka#package-babashka-script-as-a-aws-lambda).
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
- [#454](https://github.com/borkdude/babashka/issues/454): syntax check on amount of arguments to `def`
- [#458](https://github.com/borkdude/babashka/issues/458): add `clojure.data` namespace

## Prior to v0.1.0

Details about releases prior to v0.1.0 can be found
[here](https://github.com/borkdude/babashka/releases).

## Breaking changes

### v0.0.90

- The `next.jdbc` namespace and PostgresQL driver, introduced in `v0.0.89`, are
  no longer part of the standardly distributed `bb` binary. This is now
  available behind a feature flag. See [feature flag
  documentation](https://github.com/borkdude/babashka/blob/master/doc/build.md#feature-flags).
- [borkdude/babashka.curl#16](https://github.com/borkdude/babashka.curl/issues/16):
  Exceptional status codes or nonzero `curl` exit codes will throw exceptions by
  default. You can opt out with `:throw false`.

### v0.0.79
- [babashka.curl#9](https://github.com/borkdude/babashka.curl/issues/9):
  Functions in `babashka.curl` like `get`, `post`, etc. now always return a map
  with `:status`, `:body`, and `:headers`.

### v0.0.71
- [#267](https://github.com/borkdude/babashka/issues/267) Change behavior of
  reader conditionals: the `:clj` branch is taken when it occurs before a `:bb`
  branch.

### v0.0.44 - 0.0.45
- [#173](https://github.com/borkdude/babashka/issues/173): Rename `*in*` to
  `*input*` (in the `user` namespace). The reason for this is that it shadowed
  `clojure.core/*in*` when used unqualified.

### v0.0.43
- [#160](https://github.com/borkdude/babashka/issues/160): Add support for
  `java.lang.ProcessBuilder`. See docs. This replaces the `conch` namespace.
