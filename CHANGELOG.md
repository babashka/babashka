# Changelog

For a list of breaking changes, check [here](#breaking-changes)

## v0.1.1 (unreleased)

## v0.1.0

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
