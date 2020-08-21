# Changelog

For a list of breaking changes, check [here](#breaking-changes)

## Unreleased

### New

- Add `add-tap`,`tap>`, `remove-tap`, `class?`, `iterator-seq`, `remove-watch`, `realized?`
- Add `java.lang.ProcessHandle` and better support for killing subprocesses via
  Java interop. See [test script](https://github.com/borkdude/babashka/blob/7049b1b0bd582b717094703bcf299fb6363bb142/test/babashka/scripts/kill_child_processes.bb).
- Add `clojure.lang.ArityException` and tests to support https://github.com/circleci/bond library [#524](https://github.com/borkdude/babashka/issues/524) ([@cldwalker](https://github.com/cldwalker)).
- Expose more transit vars [#525](https://github.com/borkdude/babashka/issues/525) ([@djblue](https://github.com/djblue))
- Add support for `clojure.datafy`, `Datafiable` and `Navigable` [#468](https://github.com/borkdude/babashka/issues/468)
- Add support for building and running uberjars [#536](https://github.com/borkdude/babashka/issues/536)

### Fixed

- Eval metadata on var created with defn [borkdude/sci#36](https://github.com/borkdude/sci/issues/36)
- Metadata fn on defn f fails if calling defn f [borkdude/sci#363](https://github.com/borkdude/sci/issues/363)
- Allow re-binding of core vars in with-redefs [borkdude/sci#375](https://github.com/borkdude/sci/issues/375)
- Fix reading resources from jar files [#528](https://github.com/borkdude/babashka/issues/528)
- `:file` in metadata has absolute paths instead of canonical ones, to preserve symlink [#532](https://github.com/borkdude/babashka/issues/532)
- Fix `false` dynamic binding value (which was read as `nil`) [borkdude/sci#379](https://github.com/borkdude/sci/issues/379)
- Fix setting of `*warn-on-reflection*` in nREPL session [babashka/babashka.nrepl#25](https://github.com/babashka/babashka.nrepl/issues/25)
- Fix order of namespaces in uberscript [#535](https://github.com/borkdude/babashka/issues/535)

## v0.1.3 (2020-06-27)

Thanks [@llacom](https://github.com/llacom), [@AndreTheHunter](https://github.com/AndreTheHunter), [@xingzheone](https://github.com/xingzheone) for contributing to this release.

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
