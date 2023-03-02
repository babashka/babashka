# Changelog

For a list of breaking changes, check [here](#breaking-changes).

A preview of the next release can be installed from
[babashka-dev-builds](https://github.com/babashka/babashka-dev-builds).

[Babashka](https://github.com/babashka/babashka): Native, fast starting Clojure interpreter for scripting

## Unreleased

- [#1507](https://github.com/babashka/babashka/issues/1507): Expose methods on java.lang.VirtualThread ([@lispyclouds](https://github.com/lispyclouds))

## 1.2.174 (2023-03-01)

- Use GraalVM 22.3.1 on JDK 19.0.2. This adds virtual thread support. See [demo](https://twitter.com/borkdude/status/1572222344684531717).
- Expose more `jaxax.crypto` classes
- Add more `java.time` and related classes with the goal of supporting [juxt.tick](https://github.com/juxt/tick) ([issue](https://github.com/juxt/tick/issues/86))
- Compatibility with [kaocha](https://github.com/lambdaisland/kaocha) test runner
- [#1000](https://github.com/babashka/babashka/issues/1000): add lib tests for xforms ([@bobisageek](https://github.com/bobisageek))
- [#1482](https://github.com/babashka/babashka/issues/1482): make loading of libs thread safe
- [#1487](https://github.com/babashka/babashka/issues/1487): `babashka.tasks/clojure` should be supported without arguments to start a REPL
- [#1496](https://github.com/babashka/babashka/issues/1496): Add `set-agent-send-executor!` and `set-agent-send-off-executor!`
- [#1489](https://github.com/babashka/babashka/issues/1489): Don't overwrite non-empty, non-jar files when writing uberscript/uberjar ([@bobisageek](https://github.com/bobisageek))
- [#1506](https://github.com/babashka/babashka/issues/1506): `:exec-args` in task should override `:exec-args` on fn metadata
- [#1501](https://github.com/babashka/babashka/issues/1501): equals on deftype
- Add support for `.getWatches` on atoms
- Bump `babashka.fs` to `0.3.17`
- Bump `deps.clj` to `1.11.1.1237`
- Bump `babashka.http-client` to `0.1.5`
- Bump `babashka.cli` to `0.6.46`

## 1.1.173 (2023-02-04)

- [#1473](https://github.com/babashka/babashka/issues/1473): support `--config` in other dir + `:local/root` ([@lispyclouds](https://github.com/lispyclouds))
- Compatibility with `clojure.tools.namespace.repl/refresh` and `clojure.java.classpath`
- `(clojure.lang.RT/baseLoader)` now returns classloader with babashka dependencies on classpath
- Support reading tags from `data_readers.clj` and `data_readers.cljc`
- Don't exit REPL when `babashka.deps/add-deps` fails
- Fix [#1474](https://github.com/babashka/babashka/issues/1474): when `.bb` file is in different artifact, `.clj` file is loaded first if it appears first on classpath
- Support for `*loaded-libs*` and `(loaded-libs)`
- Bump rewrite-clj to `1.1.46`
- Bump http-client to `0.0.3`
- Bump fs to `0.2.15`
- Bump process to `0.4.16`

## 1.1.172 (2023-01-23)

- [#1472](https://github.com/babashka/babashka/issues/1472): fix tokenization of `babashka.tasks/clojure`: command was tokenized twice (regression was introduced in `1.0.168`)
- **BREAKING**: Bump `babashka.process`: change default for `:out :append` to `:out :write`. This default is undocumented so the impact should be small.

## 1.1.171 (2023-01-23)

- [#1467](https://github.com/babashka/babashka/issues/1467): **BREAKING**: avoid printing results, unless `--prn` is enabled (aside from `-e`, `-o` and `-O`).
- Include [http-client](https://github.com/babashka/http-client) as built-in library
- SCI: support `add-watch` on vars
- Compatibility with [eftest](https://github.com/weavejester/eftest) test runner (see [demo](https://twitter.com/borkdude/status/1616886788898885632))
- Add classes:
  - `java.util.concurrent.Callable`
  - `java.util.concurrent.ExecutorService`
- Expose `clojure.main` `main` and `repl-caught`
- Switch `clojure.test/*report-counters*` to ref instead of atom for compatibility with [kaocha](https://github.com/lambdaisland/kaocha)
- Allow `java.io.OutputStream` to be proxied, for [kaocha](https://github.com/lambdaisland/kaocha)
- Support qualified method names in `proxy` and ignore namespace

## 1.0.170 (2023-01-19)

- [#1463](https://github.com/babashka/babashka/issues/1463): Add `java.util.jar.Attributes` class ([@jeroenvandijk](https://github.com/jeroenvandijk))
- [#1456](https://github.com/babashka/babashka/issues/1456): allow `*warn-on-reflection*` and `*unchecked-math*` to be set in socket REPL and nREPL ([@axks](https://github.com/axks))
- SCI: macroexpansion error location improvement
- Add compatibility with [tab](https://github.com/eerohele/tab) and [solenoid](https://github.com/adam-james-v/solenoid)
- Bump babashka.cli and babashka.fs
- New classes:
  - `java.util.jar.Attributes`
  - `java.util.concurrent.ThreadFactory`
  - `java.lang.Thread$UncaughtExceptionHandler`
  - `java.lang.Thread$UncaughtExceptionHandler`
  - `java.util.concurrent.BlockingQueue`
  - `java.util.concurrent.ArrayBlockingQueue`
  - `java.util.concurrent.ThreadFactory`
  - `java.lang.Thread$UncaughtExceptionHandler`
  - `java.util.concurrent.Semaphore`
- Expose more httpkit.server functions: `with-channel`, `on-close`, `close`

## 1.0.169 (2023-01-03)

- Implement `ns`, `lazy-seq` as macro
- Support `--dev-build` flag in installation script
- [#1451](https://github.com/babashka/babashka/issues/1451): Allow passing explicit file and line number to clojure.test ([@matthewdowney](https://github.com/matthewdowney))
- [#1280](https://github.com/babashka/babashka/issues/1280): babashka REPL doesn't quit right after Ctrl-d ([@formerly-a-trickster](https://github.com/formerly-a-trickster) and Alice Margatroid)
- [#1446](https://github.com/babashka/babashka/issues/1446): add `pprint/code-dispatch`
- Update zlib to version `1.2.13` ([@thiagokokada](https://github.com/thiagokokada))
- [#1454](https://github.com/babashka/babashka/issues/1454): Add `babashka.process` to `print-deps` output
- Update `deps.clj` / clojure tools to `1.11.1.1208`
- Add `reader-conditional` function
- Fix pretty printing (with `clojure.pprint`) of vars
- Upgrade built-in `spec.alpha`
- SCI performance improvements: faster JVM interop

## 1.0.168 (2022-12-07)

- `loop*`, `fn*` are now special forms and `loop`, `fn`, `defn`, `defmacro`, `and` and `or` are implemented as macros. This restores compatibility with [rcf](https://github.com/borkdude/hyperfiddle-rcf)
- fs: don't touch dirs in `split-ext`
- Update to babashka process to v0.4.13: support `(process opts? & args)` syntax everywhere
- [#1438](https://github.com/babashka/babashka/issues/1438): expose `query-string` and `url-encode` functions from org.httpkit.client ([@bobisageek](https://github.com/bobisageek))
- Add `java.util.InputMismatchException`

## 1.0.167 (2022-11-30)

- [#1433](https://github.com/babashka/babashka/issues/1433): spec source as built-in fallback. When not including the
  [clojure.spec.alpha](https://github.com/babashka/spec.alpha) fork as a
  library, babashka loads a bundled version, when `clojure.spec.alpha` is required.
- [#1430](https://github.com/babashka/babashka/issues/1430): Fix issue with `bb tasks` throwing on empty display tasks list.
- Add note about BSOD when using WSL1, see [README.md/quickstart](https://github.com/LouDnl/babashka#quickstart)
- SCI: performance improvements
- Better error locations for interop ([@bobisageek](https://github.com/bobisageek))
- Fix [babashka/babashka.nrepl#59](https://github.com/babashka/babashka.nrepl/issues/59): do not output extra new line with cider pprint
- Use `namespace-munge` instead of `munge` for doing ns -> file lookup

## 1.0.166 (2022-11-24)

See the [Testing babashka scripts](https://blog.michielborkent.nl/babashka-test-runner.html) blog post for how to run tests with this release.

- Compatibility with Cognitest [test-runner](https://github.com/cognitect-labs/test-runner) and [tools.namespace](https://github.com/clojure/tools.namespace)
- Add `run-test` and `run-test-var` to `clojure.test`
- Compile distributed uberjar using GraalVM, fixes `babashka.process/exec` for Nix
- [#1414](https://github.com/babashka/babashka/issues/1414): preserve metadata on exec task function argument map
- [#1269](https://github.com/babashka/babashka/issues/1269): add lib tests for sluj ([@bobisageek](https://github.com/bobisageek))
- Update nix app example in docs
- Add `java.lang.Error` and `java.net.URLClassLoader` (only for compatibility with the `clojure.java.classpath` lib)
- Deps.clj `deps.clj: 1.11.1.1200`
- Upgrade timbre to `6.0.1`
- Performance improvements in SCI
- SCI: preserve stack information on `throw` expressions

## 1.0.165 (2022-11-01)

- Fix [#1401](https://github.com/babashka/babashka/issues/1401): mutation of `deftype` field should be visible in protocol method
- Fix [#1405](https://github.com/babashka/babashka/issues/1405): drop name metadata from conditionally defined var
- [#602](https://github.com/babashka/babashka/issues/602): add lib tests for clj-commons/fs ([@bobisageek](https://github.com/bobisageek))
- Add `java.net.URLConnection` class
- Add `java.time.zone.ZoneRules` class
- Copy more docstrings for core macros and vars
- Bump `core.async` to `1.6.673`
- Implement `in-ns` as function, rather than special form ([@SignSpice](https://github.com/SignSpice))
- Bump `deps.clj` to `1.11.1.1182`
- Bump GraalVM to `22.3.0`
- SCI: don't rely on metadata for record implementation

## 1.0.164 (2022-10-17)

- SCI: optimizations for `let`
- Add single argument read method support to PipedInputStream proxy ([@retrogradeorbit](https://github.com/retrogradeorbit))
- feat: Honor `*print-namespace-maps*` in pprint ([@ghoseb](https://github.com/ghoseb))
- [#1369](https://github.com/babashka/babashka/issues/1369): provide `.sha256` files for every released asset
- [#1397](https://github.com/babashka/babashka/issues/1397): Add `clojure.lang.Namespace` as alias for `sci.lang.Namespace`, such that `(instance? clojure.lang.Namespace *ns*)` returns `true` in bb
- [#1384](https://github.com/babashka/babashka/issues/1384): allow `.indexOf` on `LazySeq`
- [#1330](https://github.com/babashka/babashka/issues/1330): allow `(set! *warn-on-reflection*)` in programmatic nREPL
- Better error message when failing to load `bb.edn` ([@lispyclouds](https://github.com/lispyclouds))
- Pods: print and flush to `*out*` and `*err*` instead of using `println` ([@justone](https://github.com/justone))
- deps.clj: support for `CLJ_JVM_OPTS` and `JAVA_OPTS` ([@ikappaki](https://github.com/ikappaki))
- Fix `*print-namespace-maps*` when using `clojure.pprint` ([@ghoseb](https://github.com/ghoseb))
- Process: only slurp `*err*` when inputstream ([@ikappaki](https://github.com/ikappaki))
- Process: add `alive?` API function ([@grzm](https://github.com/grzm))
- Bump libraries: tools.cli, babashka.cli
- [#1391](https://github.com/babashka/babashka/issues/1391): include raw in `hiccup2.core` ns
- [#1391](https://github.com/babashka/babashka/issues/1391): support loading `hiccup.page` when adding hiccup to deps

## 0.10.163 (2022-09-24)

- [#808](https://github.com/babashka/babashka/issues/808): support `-Sdeps` option to support passing extra deps map which will be merged last
- [#1336](https://github.com/babashka/babashka/issues/1336): tasks subcommand doesn't work with global `-Sforce` option ([@bobisageek](https://github.com/bobisageek))
- [#1340](https://github.com/babashka/babashka/issues/1340): `defprotocol` methods are missing `:doc` metadata ([@bobisageek](https://github.com/bobisageek))
- [#1368](https://github.com/babashka/babashka/issues/1368): `-x`: do not pick up on aliases in `user` ns
- [#1367](https://github.com/babashka/babashka/issues/1367): Fix line number in clojure.test output ([@retrogradeorbit](https://github.com/retrogradeorbit))
- [#1370](https://github.com/babashka/babashka/issues/1370): Add `core.async` `to-chan!`, `to-chan!!`, `onto-chan!` ([@cap10morgan](https://github.com/cap10morgan))
- [#1358](https://github.com/babashka/babashka/issues/1358): Expose a subset of `java.lang.ref` to enable hooking into the destruction/GC of objects ([@retrogradeorbit](https://github.com/retrogradeorbit))
- [#1364](https://github.com/babashka/babashka/issues/1364): Be tolerant of unknown tags in `bb.edn`
- Add and expose `babashka.classes/all-classes` to get a list of all available classes (as `java.lang.Class` objects) ([@eerohele](https://github.com/eerohele))
- Add more reflection class methods ([@eerohele](https://github.com/eerohele))
- Bump `clj-yaml`
- Add `-x` help
- Set `TCP_NODELAY` in pods for performance
- Expose `clojure.main/with-bindings`
- Add `ThreadPoolExecutor` rejection policy classes ([@eerohele](https://github.com/eerohele))
- Download but don't run pods when `os.name` / `os.arch` don't match ([@cap10morgan](https://github.com/cap10morgan))
- Add `clojure.core.server/stop-server` ([@eerohele](https://github.com/eerohele))
- Add `ns-unalias`
- Add `AtomicInteger` and `AtomicLong` to full interop
- Add `PrintWriter-on`
- Improve `reify` error messages ([@retrogradeorbit](https://github.com/retrogradeorbit))
- Expose `core.async` `ManyToManyChannel`
- fs: add `write-lines`
- fs: add `write-bytes`
- [#1350](https://github.com/babashka/babashka/issues/1350): map `clojure.lang.Var` to `sci.lang.Var`
- Use temporary fork of `clj-yaml` with new `:load-all`, `:unknown-tag-fn`
  options and preserves strings with numbers that start with zeros as strings
  (this solves a problem when YAML 1.1 output is read as YAML 1.2.). Once
  upstream SnakeYAML 1.33 lands, this will be used again.

## 0.9.162 (2022-09-04)

Check out our new project: [bbin](https://github.com/babashka/bbin): install any Babashka script or project with one command. Thanks [@rads](https://github.com/rads)!

- Throw exception on attempt to reify multiple interfaces ([@retrogradeorbit](https://github.com/retrogradeorbit))
- Allow java.lang.Object reify with empty methods ([@retrogradeorbit](https://github.com/retrogradeorbit))
- [#1343](https://github.com/babashka/babashka/issues/1343): Fix postgres feature
- [#1345](https://github.com/babashka/babashka/issues/1345): add `javax.net.ssl.SSLException` and `java.net.SocketTimeoutException` classes ([@lread](https://github.com/lread))
- Fix `satisfies?` with marker protocol (no methods)
- Update `rewrite-clj`
- Update `deps.clj`
- Update `babashka.cli`
- Update `org.clj-commons/clj-yaml`
- `babashka.fs`: fix `expand-home` on Windows
- `babashka.fs`: expose `:win-exts`
- nREPL: preserve stacktrace on exception
- Fix [#1322](https://github.com/babashka/babashka/issues/1322): improve error location
- Fix [#1338](https://github.com/babashka/babashka/issues/1338): `add-watch` segfault
- Fix [#1339](https://github.com/babashka/babashka/issues/1339): resolve record name ending with dot.

## 0.9.161 (2022-07-31)

- Fix `exec`

## 0.9.160 (2022-07-29)

- Breaking: change `exec`, introduced in `0.9.159` to a function instead.
  You now write `(exec 'mynamespace.function)` instead.

## 0.9.159 (2022-07-29)

Read the introductory blog post about the new babashka CLI integration [here](https://blog.michielborkent.nl/babashka-tasks-meets-babashka-cli.html).

- [#1324](https://github.com/babashka/babashka/issues/1324): `-x` to invoke a function with babashka CLI
- [#1324](https://github.com/babashka/babashka/issues/1324): `babashka.tasks/exec` to invoke a function with babashka CLI in tasks
- SCI: don't eval metatada on defn body
- SCI issue 774: make interpreter stacktrace available to user
- `babashka.process`: improve `tokenize`
- Upgrade to GraalVM 22.2.0 (#1325)

## 0.8.157 (2022-07-01)

- Add compatibility with [`metosin/malli`](https://github.com/metosin/malli#babashka) `{:mvn/version "0.8.9"}`.
- Expose `babashka.nrepl.server/start-server!`- and `stop-server!`-functions to support programmatically starting
  an nrepl-server. `start-server!` is redefined to not require a sci-context as its first argument.
- Fix misspelling in script/uberjar: `BABASHKA_FEATURE_TRANSIT`

## 0.8.156 (2022-06-08)

- macOS aarch64 support. Upgrading via brew or the installer script should
  install the aarch64 version on an M1 system.
- Support for running [prismatic/schema](https://github.com/plumatic/schema)
  from source.  There is an open PR for babashka. Until it is merged you can use
  [this](https://github.com/borkdude/schema/tree/bb-test-suite) fork.
- SCI: many small improvements, especially in `defrecord` (discovered while
  trying to make `schema.core` work)
- Switch version schema to major.minor.release_count.
- babashka.nrepl: fix completions for static Java interop
- `fs/read-all-lines`, support charset
- fs: `strip` and `split-ext` are less reliant on file system and are now just
  string operations
- Bump cheshire
- Add `babashka.process/exec` for exec call (replacing the current process)
- Improve `babashka.process/tokenize`
- [#1264](https://github.com/babashka/babashka/issues/1264): add support for calling `ScheduledExecutorService`
- Add support for `sun.misc.SignalHandler`
- Add `java.net.BindException`, `clojure.lang.AFunction`, `AbstractMethodError`
- Upgrade httpkit to `2.6.0-RC1`
- Add `process/shell`, similar to `babashka.tasks/shell` but in process ns
- pods: fix benign socket closed exception error output

## 0.8.2 (2022-05-06)

- Convey `*print-length*` to pprint and allow `set!`
- `babashka.nrepl`: support pprint length
- SCI: support invoking field, without dash
- Add compatibility with clojure numeric tower
- Update deps.clj to tools jar `1.11.1.1113`
- Compatibility with fipp and puget
- Fix [#1233](https://github.com/babashka/babashka/issues/1233): don't print error to stdout in prepl
- Update process with `:pre-start-fn` option
- Update fs with `cwd` and Windows glob improvements
- Expose edamame, fixes [#549](https://github.com/babashka/babashka/issues/549) and [#1258](https://github.com/babashka/babashka/issues/1258) (#1259)
- Support `BABASBHKA_PODS_DIR` environment variable

## 0.8.1 (2022-04-15)

- Add `prepare` subcommand to download deps & pods and cache pod metadata
- [#1041](https://github.com/babashka/babashka/issues/1041): Improve error message when regex literal in EDN config
- [#1223](https://github.com/babashka/babashka/issues/1223): Ensure that var metadata (specifically `:name`) aligns with the var's symbol (which in turn ensures that `doc` will print the var's name)
- [#556](https://github.com/babashka/babashka/issues/556) Add server-status to org.httpkit.server
- [#1231](https://github.com/babashka/babashka/issues/1231): missing methods in `reify` should fall back to default interface methods
- Fix destructuring in defrecord protocol impls
- Support `*print-dup*`

## 0.8.0 (2022-04-04)

### New

- [#863](https://github.com/babashka/babashka/issues/863): allow pods to be declared in `bb.edn` and load them when required. See [pod library docs](https://github.com/babashka/pods#in-a-babashka-project) and the entry in the [babashka book](https://book.babashka.org/#_pods_in_bb_edn) for details.

### Enhanced

- [#1216](https://github.com/babashka/babashka/issues/1216): support `core.async/alts!` using polyfill
- [#1220](https://github.com/babashka/babashka/issues/1220): add reflection on java.util.concurrent.Future
- [#1211](https://github.com/babashka/babashka/issues/1211): return exit code 130 on sigint
- [#1224](https://github.com/babashka/babashka/issues/1224): add `proxy` support for `java.io.PipedInputStream` and `java.io.PipedOutputStream`. Add reflection for `java.utils.Scanner`.
- [babashka.curl#43](https://github.com/babashka/babashka.curl/issues/43) fix: last set-cookie headers on a page overwrites the ones before
- [#1216](https://github.com/babashka/babashka/issues/1216): fix `core.async` alts example with polyfill
- SCI: support `let*` special form
- Add compatibility with GraalVM 22.1
- Bump timbre
- Bump Clojure to 1.11.0
- Pods: support Rosetta2 fallback
- Process: fix for pprint
- Fs: improvement for which: do not match on local paths
- Proxy support for PipedInputStream and PipedOutputStream
- Expose `java.util.Scanner` for interop
- Bump Selmer
- Add `->Eduction`
- Add `*unchecked-math` for compatibility

## 0.7.8 (2022-03-13)

This release improves compatibility with several libraries: [loom](https://github.com/aysylu/loom), [hugsql.core](https://www.hugsql.org) and [specter](https://github.com/redplanetlabs/specter)!

To use specter in babashka, use the following coordinates:

``` clojure
{:deps {com.rpl/specter {:mvn/version "1.1.4"}}}
```

- Add `clojure.data.priority-map` as built-in library - this makes babashka compatible with [aysylu/loom](https://github.com/aysylu/loom)
- Add part of `clojure.tools.reader.reader-types` to support [hugsql.core](https://www.hugsql.org)
- [#1204](https://github.com/babashka/babashka/issues/1204) add property `babashka.config` to reflect `bb.edn` location ([@mknoszlig](https://github.com/mknoszlig))
- Several fixes and enhancements to run Red Planet Lab's [specter](https://github.com/borkdude/specter/commit/8ba809a2cd35d3b6f8c5287e6bd3b4e06e42f6dc) library in babashka
- [#1199](https://github.com/babashka/babashka/issues/1199): support `print-method` impls on records
- `babashka.fs`: add `windows?` predicate
-  SCI: add `*clojure-version*` and `(clojure-version)`
- Add `java.util.zip.Deflater` and `java.util.zip.DeflaterOutputStream`
- SCI: implement `declare` as macro
- [#938](https://github.com/babashka/babashka/issues/938): drop location metadata from symbols, except top level

## 0.7.7 (2022-03-04)

### New

- [#1187](https://github.com/babashka/babashka/issues/1187) tasks: Add `:result` key to `(current-task)` map that can be used in `:leave` task hook
- [#1192](https://github.com/babashka/babashka/issues/1192): expose `*assert*`
- Add `clojure.instant/parse-timestamp`
- Compatibility with [at-at](https://github.com/overtone/at-at) by adding:
  - `java.text.SimpleDateFormat`
  - `java.util.concurrent.ThreadPoolExecutor`
  - `java.util.concurrent.ScheduledThreadPoolExecutor`
- Add `pprint/get-pretty-writer`

### Enhancements

- [#1190](https://github.com/babashka/babashka/issues/1190) tasks: task dependencies resolve as core.async channels
- Bump tools deps jar to `1.10.3.1087`

## 0.7.6 (2022-02-24)

Please leave some feedback about babashka in the [2022 Q1 Survey](https://forms.gle/ko3NjDg2SwXeEoNQ9)!

- SCI performance improvements
- Bump clj-yaml to 0.7.1.108
- [#1181](https://github.com/babashka/babashka/issues/1181): clojure.test report does not respect *test-out* correctly
- [#1180](https://github.com/babashka/babashka/issues/1180): limit bb tasks output to first line of docstring
- babashka.process: support appending to `:out` file
- babashka.fs: add `create-temp-file`, `read-attributes*`, `zip`

## 0.7.5 (2022-02-16)

Please leave some feedback about babashka in the [2022 Q1 Survey](https://forms.gle/ko3NjDg2SwXeEoNQ9)!

- SCI: Performance improvements for loops and varargs function invocation.
- Fix [#1170](https://github.com/babashka/babashka/issues/1170): `defmacro` with a `defrecord` inside fails to resolve classname in protocol implementation.
- Bump deps.clj to tools jar `1.10.3.1082`.
- Upgrade to GraalVM 22.0.0.2.
- Add `halt-when`.
- Add `java.io.Data` classes
- Add compatibility with [clojure-msgpack](https://github.com/edma2/clojure-msgpack)
- Upgrade to clojure 11 beta1
- Bump transit to 1.0.329

## 0.7.4 (2022-01-25)

Please leave some feedback about babashka [here](https://forms.gle/ko3NjDg2SwXeEoNQ9).

- Add new namespace from clojure 1.11: `clojure.math`
- Add new vars from clojure 1.11: `abs`, `iteration`
- Add compatibility with `org.clojure/algo.monads`
- SCI: support `:as-alias`
- SCI: add `pop!` ([@kbaba1001](https://github.com/kbaba1001))
- `deps.clj`: update to clojure CLI 1.10.3.1058
- Add metabom jar to docker images [#1133](https://github.com/babashka/babashka/issues/1133) ([@kipz](https://github.com/kipz), [@lispyclouds](https://github.com/lispyclouds))
- Add opencontainers annotations to docker image [#1134](https://github.com/babashka/babashka/issues/1134) ([@kipz](https://github.com/kipz), [@lispyclouds](https://github.com/lispyclouds))
- Fix Alpine Linux Docker images in CI script [#1140](https://github.com/babashka/babashka/issues/1140) ([@kipz](https://github.com/kipz), [@lispyclouds](https://github.com/lispyclouds))
- `babashka.fs`: create dirs in `copy-tree` ([@duzunov](https://github.com/duzunov))
- SCI: fix order of metadata evaluation ([@erdos](https://github.com/erdos))
- Fix: cannot take value of macro of `->`
- Fix [#1144](https://github.com/babashka/babashka/issues/1144): cannot create multidimensional arrays
- Fix [#1143](https://github.com/babashka/babashka/issues/1143): allow optional (ignored) `--` when using using `--main` ([@grzm](https://github.com/grzm))
- SCI: throw when `recur` is used from non-tail position
- Add more libraries to CI lib tests ([@cljwalker](https://github.com/cljwalker))
- Upgrade several built-in deps: `org.clojure/clojure`, `cheshire`, `core.async`, `test.check`

## 0.7.3 (2021-12-30)

- Do not require java for bb tasks without deps [#1123](https://github.com/babashka/babashka/issues/1123), [#1124](https://github.com/babashka/babashka/issues/1124)

## 0.7.2 (2021-12-29)

- Add `spy` macro from `taoensso.timbre` [#1087](https://github.com/babashka/babashka/issues/1087)
- Better error for higher order fn arity mismatch
- Check `shasum` / `sha256sum` in `PATH` on install script ([@thiagokokada](https://github.com/thiagokokada))
- Build arm64 docker image in CI [#1099](https://github.com/babashka/babashka/issues/1099) ([@cap10morgan](https://github.com/cap10morgan))
- Upgrade to `edamame` v0.0.19
- Load tasks and deps from other bb.edn file using `--config` and `--deps-root` options [#1110](https://github.com/babashka/babashka/issues/1110)
- Uberscript improvements [#584](https://github.com/babashka/babashka/issues/584), [#1037](https://github.com/babashka/babashka/issues/1037)
- Include native elements in printed stacktrace [#1105](https://github.com/babashka/babashka/issues/1105)
- Missing error message when exception happens in REPL print [#1116](https://github.com/babashka/babashka/issues/1116)
- Undeprecate `$` in babashka.process
- Add lots of library tests to CI ([@cldwalker](https://github.com/cldwalker))
- Release SNAPSHOT builds to
  [babashka-dev-builds](https://github.com/babashka/babashka-dev-builds/releases)
  (use only for testing)

## 0.7.0 (2021-12-10)

- Add compatibility with `clojure.spec.alpha`. See
  [babashka/spec.alpha](https://github.com/babashka/spec.alpha) and this [blog
  post](https://blog.michielborkent.nl/using-clojure-spec-alpha-with-babashka.html).
- Add `to-array-2d`, `aclone`, `areduce` and `amap` ([@MrEbbinghaus](https://github.com/MrEbbinghaus))
- Add `inst-ms`
- Add `java.util.StringTokenizer`
- `clojure.core/read` can read with `PushbackReader` that is not `LineNumberingPushbackReader`
- Allow macroexpand on `for` and `doseq` ([@erdos](https://github.com/erdos))
- Add `clojure.instant/read-instant-date`
- Add `StackTraceElement->vec`
- Add `memfn`
- Implement Java field access (for `clojure.spec.alpha`)
- Warn on requiring `clojure.spec.alpha`, suggesting to use [babashka/spec.alpha](https://github.com/babashka/spec.alpha)
- Warn on requiring `clojure.core.specs.alpha`, suggesting to explicitly add it to deps
- Deprecate `$` in babashka.process (still available, but no longer recommended)

## 0.6.8 (2021-12-02)

- Add `reader-conditional?`, `test`
- Deps.clj: bump to tools jar `1.10.3.1040`
- Babashka.process: support `deref` with timeout ([@SevereOverfl0w](https://github.com/SevereOverfl0w))
- Add new functions from clojure 1.11 alpha 3 except `clojure.java.math`
- nREPL server: catch `Throwable` instead of `Exception`, fixes hanging with `assert`

## 0.6.7 (2021-11-29)

Minor bugfix release:

- `print-deps` included `:local/root` dependency which is not valid outside babashka repo
- `fs/which` edge case improvement on Windows [#1083](https://github.com/babashka/babashka/issues/1083)

## 0.6.6 (2021-11-29)

-  Resolve program in `babashka.process` on Windows using known extensions. This
   means you can now invoke `(shell "yarn")` and it will work on both Unix-like
   systems and Windows.
- Add `babashka.core` namespace with `windows?` predicate.
- Add `fs/with-temp-dir` to `babashka.fs` ([@hugoduncan](https://github.com/hugoduncan))
- Add `fs/home` and `fs/expand-home` to `babashka.fs` ([@Kineolyan](https://github.com/Kineolyan))
- `clojure.core/read` improvements: support `:eof` + `:read-cond`
- Add support `*read-eval*`, `*default-reader-fn*` and `*reader-resolver*` dynamic vars to be used with `clojure.core/read`.
- Add `SQLITE` feature flag ([@nikvdp](https://github.com/nikvdp))
- Add `javax.crypto.Mac` and `javax.crypto.spec.SecretKeySpec` classes to support development of [yaaws-api](https://github.com/grzm/yaaws-api) ([@grzm](https://github.com/grzm))
- Add `java.net.Inet4Address` and `java.net.Inet6Address` classes
- Fix `--version` option, don't read cp file. [#1071](https://github.com/babashka/babashka/issues/1071) ([@bobisageek](https://github.com/bobisageek))
- Add classes to support running the
  [xforms](https://github.com/cgrand/xforms) library from source:
  - `java.util.ArrayDeque`
  - `java.util.ArrayList`
  - `java.util.Collections`
  - `java.util.Comparator`
  - `java.util.NoSuchElementException`
- `babashka.curl`: support `:as :bytes` option to download binary file
- Add compatibility with [hato](https://github.com/gnarroway/hato) and
  [clj-http-lite](https://github.com/clj-commons/clj-http-lite) insecure feature
  by adding classes:
  - `java.net.CookiePolicy`
  - `java.net.http.HttpTimeoutException`
  - `javax.net.ssl.HostnameVerifier`
  - `javax.net.ssl.HttpsURLConnection`
  - `javax.net.ssl.KeyManagerFactory`
  - `javax.net.ssl.SSLSession`
  - `javax.net.ssl.TrustManagerFactory`
  - `java.security.KeyStore`
  - `java.util.zip.Inflater`
  - `java.util.zip.ZipException`

## 0.6.5 (2021-11-13)

- Compatibility with a [fork of
  tools.namespace](https://github.com/babashka/tools.namespace). This allows
  running the Cognitect
  [test-runner](https://github.com/cognitect-labs/test-runner) (Cognitest) from source.
- Add various `tools.build` related classes for running parts of tools.build
  with bb ([@hugoduncan](https://github.com/hugoduncan)). Keep an eye on [this
  repo](https://github.com/babashka/tools.bbuild).
- Deps.clj: upgrade tools jar, support checking manifest staleness (see [TDEPS-83](https://clojure.atlassian.net/browse/TDEPS-83))
- Add `clojure.lang.IPersistentList` ([@FieryCod](https://github.com/FieryCod))
- The [contajners](https://github.com/lispyclouds/contajners) library is now compatible with babashka
- Support `Object` `toString` override on defrecords [#999](https://github.com/babashka/babashka/issues/999).
- Bump to GraalVM 21.3.0 JVM 11
- Bump `core.async` to `1.4.627`
- Bump edamame to `v0.0.14`, fixes parsing of `foo@bar`
- Fix segfault when reifying `FileVisitor` with `visitFileFailed` [#1055](https://github.com/babashka/babashka/issues/1055)
- Add `PGProperty` fields to reflection config (fix for postgres feature flag) [#1046](https://github.com/babashka/babashka/issues/1046) ([@bobisageek](https://github.com/bobisageek))
- Fix for `babashka.fs/unzip` when entry in dir comes before dir in entries
- Calling `.close` on `DirectoryStream` fails [#1061](https://github.com/babashka/babashka/issues/1061)
- Add support for `java.nio.file.attribute.BasicFileAttributes`

## 0.6.4

- Fix for `DEPS_CLJ_TOOLS_VERSION` introduced in deps.clj bundled in 0.6.3

## 0.6.3

- Add `java.security.Provider` and `java.security.Security`. This adds compatibility with [clj-commons/digest](https://github.com/clj-commons/digest).
- Fix mapping for `babashka.fs/unzip` [#1030](https://github.com/babashka/babashka/issues/1030)
- Pods: support metadata in pod vars, like docstrings ([@quoll](https://github.com/quoll))
- babashka.curl: support `:follow-redirects false` ([@sudorock](https://github.com/sudorock))
- Add support for `--init` as a file to be loaded before evaluation actions [#1033](https://github.com/babashka/babashka/issues/1033) ([@bobisageek](https://github.com/bobisageek))
- Bump rewrite-clj to v1.0.699-alpha ([@yannvanhalewyn](https://github.com/yannvanhalewyn))
- Fix `BABASHKA_FEATURE_POSTGRESQL` feature flag, initialize `java.sql.SQLException` build time [#1040](https://github.com/babashka/babashka/issues/1040)
- Deps.clj: upgrade to tools version `1.10.3.998` and include new
  `DEPS_CLJ_TOOLS_VERSION` environment variable to use older or newer tools jar.

## 0.6.2

- Add `java.util.zip.ZipFile`, `java.util.stream.BaseStream`
- Fix data reader for clojure.data.xml and `*input*` [#1004](https://github.com/babashka/babashka/issues/1004) ([@bobisageek](https://github.com/bobisageek))
- Upgrade `deps.clj` (matches clojure CLI `1.10.986`)
- Print locals only when debug option is enabled [#1015](https://github.com/babashka/babashka/issues/1015)

### SCI:

- Fix order of protocol handling wrt/ `extend-type`, `defrecord` and `:extend-via-metadata`

### babashka.curl:

- Support keywords as query and form param keys ([@lispyclouds](https://github.com/lispyclouds))

### babashka.nrepl:

- Fix hanging CIDER [#45](https://github.com/babashka/babashka.nrepl/issues/45)

### babashka.fs:

-  Add `fs/unzip`

## 0.6.1

- Fix for `java-http-clj` `send-async` [#997](https://github.com/babashka/babashka/issues/997)
- Add `PipedInput/OutputStream` [#1001](https://github.com/babashka/babashka/issues/1001)
- `aarch64` static binaries are compiled with `"-H:+StaticExecutableWithDynamicLibC"`

SCI:

- Self-referential function returns wrong binding in presence of laziness
  [#1006](https://github.com/babashka/babashka/issues/1006)

## 0.6.0

- Support for `java.net` HTTP Client. This enables running
  [java-http-clj](https://github.com/schmee/java-http-clj) from source. The raw
  interop is the first part of a multi-stage plan to move all http related
  scripting towards `java.net.http` in favor of the other two solutions
  currently available in `bb`: `babashka.curl` and `org.httpkit.client`. ([@grzm](https://github.com/grzm))
- Add `*print-readably*` and `*flush-on-newline*` [#959](https://github.com/babashka/babashka/issues/959)
- Support `:clojure.core/eval-file metadata` [#960](https://github.com/babashka/babashka/issues/960)
- Add `clojure.data.xml/alias-uri` [#995](https://github.com/babashka/babashka/issues/995)
- Make REPL requires available in expression on command line [#972](https://github.com/babashka/babashka/issues/972) ([@bobisageek](https://github.com/bobisageek))
- Fix docstrings and metadata for large number of vars [#957](https://github.com/babashka/babashka/issues/957) ([@bobisageek](https://github.com/bobisageek))
- Upgrade `deps.clj` / `tools.jar` to match Clojure CLI 1.10.3.967
- Add (limited) support for `java.lang.reflect.Method` (`getName` only) [#969](https://github.com/babashka/babashka/issues/969)
- Use tagged-literal for unknown tags when reading EDN via `*input*` [#975](https://github.com/babashka/babashka/issues/975) ([@bobisageek](https://github.com/bobisageek))
- Logging feature flag and `tools.logging.readable` [#981](https://github.com/babashka/babashka/issues/981) ([@bobisageek](https://github.com/bobisageek))
- Migrate GraalVM config to uberjar [#973](https://github.com/babashka/babashka/issues/973) ([@ericdallo](https://github.com/ericdallo))
- Upgrade to GraalVM 21.2.0

## 0.5.1

- Add new `print-deps` subcommand for printing a `deps.edn` map and classpath
  which includes all built-in deps. This can be used for editor tooling like
  Cursive and clojure-lsp, but also for migrating a babashka project to a Graal
  native-image project.
- Upgrade `clj-yaml` to 0.7.107 which adds two new vars: `generate-stream`, `parse-stream`
- Add `timbre/merge-config!`
- Add `strip-ext` to `babashka.fs` ([@corasaurus-hex](https://github.com/corasaurus-hex))
- Fixed: `with-open` fails on `java.io.PrintWriter` [#953](https://github.com/babashka/babashka/issues/953)
- Upgrade `deps.clj` to match Clojure CLI `1.10.3.933`
- Upgrade several other deps

## 0.5.0

Babashka proper:

- Add `clojure.tools.logging` with `taoensso.timbre` as the default implementation
- Source compatibility with `org.clojure/data.json {:mvn/version "2.4.0"}`
- Support `pprint/formatter-out` [#922](https://github.com/babashka/babashka/issues/922)
- Support `pprint/cl-format` with `with-out-str` [#930](https://github.com/babashka/babashka/issues/930)
- Support passing `GITLIBS` via `:extra-env` in `clojure` to set git lib dir:
  `(clojure {:extra-env {"GITLIBS" ".gitlib"}} ...)` [#934](https://github.com/babashka/babashka/issues/934)
- Add `--force` option to force recomputation of babashka deps classpath.
- Add `java.io.FileInputStream`. This fixes compatibility with [replikativ/hasch](https://github.com/replikativ/hasch).
- Update Selmer to `1.12.44`, include `<<` interpolation macro
- Passing form on Windows with question mark breaks evaluation [#889](https://github.com/babashka/babashka/issues/889)
- Fix `(read-line)` in REPL [#899](https://github.com/babashka/babashka/issues/899)
- `babashka.tasks/clojure` with `:dir` option doesn't resolve deps in `:dir` [#914](https://github.com/babashka/babashka/issues/914)

Deps.clj:

Update to v0.0.16 which corresponds to clojure CLI `1.10.3.855`.

Sci:

- Perf improvements
- `case` expression generated from macro doesn't work correctly
- Fix stacktrace with invalid import [borkdude/sci#589](https://github.com/borkdude/sci/issues/589)

Special thanks to:

- [@bobisageek](https://github.com/bobisageek) for spending time and energy on
getting the majority of tests to work on Windows.
- [@ptaoussanis](https://github.com/ptaoussanis) for creating [timbre](https://github.com/ptaoussanis/timbre).
- [@puredanger](https://github.com/puredanger) for accepting patches to
  [data.json](https://github.com/clojure/data.json) which made it compatible
  with babashka.

## 0.4.6

- Upgrade to GraalVM 21.1, fixes [#884](https://github.com/babashka/babashka/issues/884)

## 0.4.5

Babashka proper:

- Add `java.net.InetSocketAddress`
- Add support for slingshot [#675](https://github.com/babashka/babashka/issues/675)
- Add STM facilities (`dosync`, `ref`, etc.)
- Fix `.wait`, `.notify` interop on arbitrary classes

Deps.clj (used for resolving deps and `clojure` invocations):

- Fix JVM option parsing [#46](https://github.com/borkdude/deps.clj/issues/46)

Sci: various minor performance improvements.

## 0.4.4

Babashka proper:

- Print ex-data in error report [#730](https://github.com/babashka/babashka/issues/730) ([@GreshamDanielStephens](https://github.com/GreshamDanielStephens), [@rng-dynamics](https://github.com/rng-dynamics))
- Tasks: support dynamic vars [#865](https://github.com/babashka/babashka/issues/865)
- Tasks: use stable namespace when using `run` [#865](https://github.com/babashka/babashka/issues/865)
- Add `java.lang.ProcessHandle$Info` [#872](https://github.com/babashka/babashka/issues/872)
- Add `java.util.Optional` [#872](https://github.com/babashka/babashka/issues/872)
- Add `java.lang.StackTraceElement` (to gain compatibility with libraries such as [omniconf](https://github.com/grammarly/omniconf))

Babashka.nrepl:

- Error reporting improvement [#40](https://github.com/babashka/babashka.nrepl/issues/865)

Sci:

- Support trailing metadata in `defn`

## 0.4.3

- Add `cognitect.transit/tagged-value`, needed for sql pods

## 0.4.2

Babashka proper:

- Improve `clojure.java.browse`, add `*open-url-script*` [#846](https://github.com/babashka/babashka/issues/846) ([@thiagokokada](https://github.com/thiagokokada))
- Add `--checksum` option to install script [#842](https://github.com/babashka/babashka/issues/842) ([@thiagokokada](https://github.com/thiagokokada))
- Add more agent functions and fix binding conveyance
- Better error handling for parallel tasks [#847](https://github.com/babashka/babashka/issues/847)
- Use `sequential?` for identifying if the script output needs splitting ([@arichiardi](https://github.com/arichiardi))

Babashka.pods:

- Allow pods to set custom transit read and write handlers

## 0.4.1

- Base static linux build on musl. The binary should now work in most linuxes
  out of the box. ([@lispyclouds](https://github.com/lispyclouds),
  [@thiagokokada](https://github.com/thiagokokada))
- Add `NullPointerException` to default imports
- Add `thread-bound?` function
- Expose escaping functions from `selmer.util` [#835](https://github.com/babashka/babashka/issues/835)
- Fix Windows GraalVM config for httpkit
- Add `:error-fn` option to `shell` [#836](https://github.com/babashka/babashka/issues/836)
- Add `babashka.task` `System` property [#837](https://github.com/babashka/babashka/issues/837)
- Allow thread-first with `shell` like `babashka.process` [#829](https://github.com/babashka/babashka/issues/829)

## 0.4.0

Babashka proper:

- Add [Task runner](https://book.babashka.org/#tasks) feature
- Add `java.util.zip.ZipOutputStream` class
- Add `java.text.ParseException` exception class to support
  [jasentaa](https://github.com/rm-hull/jasentaa) parser combinator library
- Update Selmer to 1.12.40

Sci:

- Better error msg for protocol not found or class

## 0.3.8

- Add [Selmer](https://github.com/yogthos/Selmer) to built-in libraries [#821](https://github.com/babashka/babashka/issues/821)
- Don't throw when `PATH` isn't set during deps resolving ([@ieugen](https://github.com/ieugen))
- Add `with-precision` macro [#662](https://github.com/babashka/babashka/issues/662)
- Task changes and improvements, see [issue](https://github.com/babashka/babashka/issues/778)

## 0.3.7

- Ignore local and global deps.edn when resolving deps for `bb.edn` [#807](https://github.com/babashka/babashka/issues/807)
- Use `IllegalArgumentException` when throwing on duplicate case constants [#809](https://github.com/babashka/babashka/issues/809)
- Fix --classpath when no additional args are passed [#811](https://github.com/babashka/babashka/issues/811)
- Task changes and improvements, see [issue](https://github.com/babashka/babashka/issues/778)

## 0.3.6

Babashka proper:

- Add check for arg count to `for` macro [#767](https://github.com/babashka/babashka/issues/767)
- Ignore empty classpath entries [#780](https://github.com/babashka/babashka/issues/780)
- Fix uberjar CLI parsing and throw when no classpath is provided [#780](https://github.com/babashka/babashka/issues/780)
- Support `:min-bb-version` in `bb.edn` See [docs](https://book.babashka.org/#_bb_edn). [#663](https://github.com/babashka/babashka/issues/663)
- Tasks improvements. See [issue](https://github.com/babashka/babashka/issues/778).

Deps.clj:

- Windows fixes

Babashka.fs:

- Allow dir as dest in `copy` [#24](https://github.com/babashka/fs/issues/24)
- Allow dir as dest in `move` [#25](https://github.com/babashka/fs/issues/25)

## 0.3.5

- Support [binf.cljc](https://github.com/helins/binf.cljc) library by adding `ByteBuffer` and `Charset`-related classes [#784](https://github.com/babashka/babashka/issues/784)
- Tasks improvements. See [issue](https://github.com/babashka/babashka/issues/778).
- Add `java.security.SecureRandom` [#790](https://github.com/babashka/babashka/issues/790)

Sci:

- Add `aset-byte`, `aset-boolean`, `aset-short`, etc.
- Add `bit-clear`
- Add `bound-fn` and `bound-fn*`

## v0.3.4

Babashka:

- Tasks improvements. See [issue](https://github.com/babashka/babashka/issues/778).

Deps.clj:

- Fix arg parsing when invoking from Powershell [#42](https://github.com/borkdude/deps.clj/issues/42)

## v0.3.3

Babashka:

- Fix building uberjar with classpath from `bb.edn` [#776](https://github.com/babashka/babashka/issues/776)
- Provide linux arm64 static binaries [#782](https://github.com/babashka/babashka/issues/782)
- Upgrade to Clojure `1.11.0-alpha1` to get full map passing to kwargs function support
- First cut of bb tasks [#778](https://github.com/babashka/babashka/issues/778). This is a preview version which is expected to
  change. Please try it out but use with caution.

Deps.clj:

- Bump tools jar to to 1.10.3.822

Fs:

- `delete-tree` should not throw when dir does not exist [#22](https://github.com/babashka/fs/issues/22)

Sci:

- Bug with protocol methods in record where later arg overrides "this" [#557](https://github.com/borkdude/sci/issues/557)
- Support `:rename` in `:refer-clojure` [#558](https://github.com/borkdude/sci/issues/558)

## 0.3.2

- Include [rewrite-clj](https://github.com/clj-commons/rewrite-clj) into babashka [#769](https://github.com/babashka/babashka/issues/769) .

## 0.3.1

Babashka proper:

- Support `bb.edn` project config with `:paths` and `:deps`. See [docs](https://book.babashka.org/index.html#_bb_edn).
- Rewrite CLI arg parsing to to subcommand style invocations: `bb --uberjar` becomes `bb uberjar`
- Support fully qualified symbol in `--main` option [#758](https://github.com/babashka/babashka/issues/758). See [docs](https://book.babashka.org/index.html#_invoking_a_main_function ).
- Support new `doc` option to retrieve a docstring from the command line

Babashka.fs:

- Create target dir automatically in `copy-tree`

Babashka.nrepl:

- Implement `cider-nrepl` `info` / `lookup` op [#30](https://github.com/babashka/babashka.nrepl/issues/30) ([@brdloush](https://github.com/brdloush))

Babashka.process:

- Support tokenizing single string [#39](https://github.com/babashka/process/issues/39)
- Support `:extra-env` option [#40](https://github.com/babashka/process/issues/40)

Deps.clj:

- Catch up with Clojure CLI 1.10.3.814 [#40](https://github.com/borkdude/deps.clj/issues/40)

Sci:

- Support new kwargs handling from 1.11.0 [#553](https://github.com/borkdude/sci/issues/553)
- Allow dynamic `:doc` on `def`/`defn` [#554](https://github.com/borkdude/sci/issues/554)

## 0.3.0

### New

- Linux support for AArch64 [#241](https://github.com/babashka/babashka/issues/241). This means you can now run babashka on Raspberry Pi 64bit and Chromebooks with ARM 64-bit processors!

A major thanks to [CircleCI](https://circleci.com/) for enabling AArch64 support
in the babashka organization and [GraalVM](http://graalvm.org/) for supporting this platform.

### Enhancements / fixes

- Fix `print-method` when writing to stdout [#667](https://github.com/babashka/babashka/issues/667)
- Fix interop with `System/out` [#754](https://github.com/babashka/babashka/issues/754)
- Support [version-clj](https://github.com/xsc/version-clj) v2.0.1 by adding `java.util.regex.Matcher` to the reflection config
- Distribute linux and macOS archives as `tar.gz`. The reason is that `unzip` is
  not pre-installed on most unix-y systems. ([@grazfather](https://github.com/grazfather))

Babashka.fs:

- Fix globbing on Windows
- Fix Windows tests
- Fix issue with `copy-tree` when dest dir doesn't exist yet

Thanks [@lread](https://github.com/lread) for his help on fixing issues with Windows.

Sci:

- Support `:reload-all` [#552](https://github.com/borkdude/sci/issues/552)
- Narrow `reify` to just one class. See discussion in
  [sci#549](https://github.com/borkdude/sci/issues/549).
- Add preliminary support for `proxy` (mainly to support pathom3 smart maps)
  [sci#550](https://github.com/borkdude/sci/issues/550).

Thanks to [@wilkerlucio](https://github.com/wilkerlucio) and
  [@GreshamDanielStephens](https://github.com/GreshamDanielStephens) for their
  help and discussions.

## v0.2.13

### Enhancements / fixes

- Add more interfaces to be used with `reify` ([@wilkerlucio](https://github.com/wilkerlucio)) (mostly to support smart maps with [pathom3](https://github.com/wilkerlucio/pathom3))

Babashka.curl:

- Use `--data-binary` when sending files or streams [#35](https://github.com/babashka/babashka.curl/issues/35)

Babashka.fs:

- Add `create-link` and `split-paths` ([@eamonnsullivan](https://github.com/eamonnsullivan))
- Add `split-ext` and `extension` ([@kiramclean](https://github.com/kiramclean))
- Add `regular-file?`([@tekacs](https://github.com/tekacs))
- Globbing is always recursive but should not be [#18](https://github.com/babashka/fs/issues/18)

Sci:

- Allow combinations of interfaces and protocols in `reify` [#540](https://github.com/borkdude/sci/issues/540)
  ([@GreshamDanielStephens](https://github.com/GreshamDanielStephens))
- Fix metadata on non-constant map literal expression [#546](https://github.com/borkdude/sci/issues/546)

## 0.2.12

### Enhancements / fixes

- Fix false positive cyclic dep problem with doric lib [#741](https://github.com/babashka/babashka/issues/741)

## 0.2.11

### Enhancements / fixes

- Use default `*print-right-margin*` value from `clojure.pprint`
- Upgrade httpkit to 2.5.3 [#738](https://github.com/babashka/babashka/issues/738)
- Upgrade tools.cli to 1.0.206
- Add several classes to be used with `defprotocol` (`PersistentVector`, `PersistentHashSet`, ...)
- Support reifying `clojure.lang.IFn` and `clojure.lang.ILookup`

Sci:

- Detect cyclic load dependencies [#531](https://github.com/borkdude/sci/issues/531)
- Pick fn arity independent of written order [#532](https://github.com/borkdude/sci/issues/532) ([@GreshamDanielStephens](https://github.com/GreshamDanielStephens))
- `(instance? clojure.lang.IAtom 1)` returns `true` [#537](https://github.com/borkdude/sci/issues/537)
- Add `dissoc!`([@wilkerlucio](https://github.com/wilkerlucio))
- Add `force`
- Fix `ns-unmap` on referred var [#539](https://github.com/borkdude/sci/issues/539)

Babashka.nrepl:

- Fix printing in lazy value [#36](https://github.com/babashka/babashka.nrepl/issues/36)
- Update link in nREPL server message [#37](https://github.com/babashka/babashka.nrepl/issues/37)

## 0.2.10

Sci:

- Priorize referred vars over vars in current ns [#527](https://github.com/borkdude/sci/issues/527)
- If with falsy literal returns nil [#529](https://github.com/borkdude/sci/issues/529)

## 0.2.9

### New

- Include [babashka.fs](https://github.com/babashka/fs)

### Enhancements / fixes

- Upgrade to GraalVM 21.0.0 [#712](https://github.com/babashka/babashka/issues/712)

Babashka.nrepl:

- Implement pprint support [#18](https://github.com/babashka/babashka.nrepl/issues/18) ([@kolharsam](https://github.com/kolharsam), [@grazfather](https://github.com/grazfather), [@bbatsov](https://github.com/bbatsov))

Sci:

- Fix error reporting in case of arity error [#518](https://github.com/borkdude/sci/issues/518)
- Shadowing record field names in protocol functions [#513](https://github.com/borkdude/sci/issues/513)
- Fix destructuring in protocol method for record [#512](https://github.com/borkdude/sci/issues/512)
- Faster processing of maps, sets and vectors [#482](https://github.com/borkdude/sci/issues/482)
- Prioritize current namespace vars in syntax quote [#509](https://github.com/borkdude/sci/issues/509)
- Fix ns-publics to not include refers [#520](https://github.com/borkdude/sci/issues/520)
- Add `refer-clojure` macro [#519](https://github.com/borkdude/sci/issues/519)

## v0.2.8

### New

- Include [clojure.core.match](https://github.com/clojure/core.match) [#594](https://github.com/babashka/babashka/issues/594)
- Include [hiccup](https://github.com/weavejester/hiccup) [#646](https://github.com/babashka/babashka/issues/646)
- Include [clojure.test.check](https://github.com/clojure/test.check) [#487](https://github.com/babashka/babashka/issues/487). Included namespaces:
  - clojure.test.check
  - clojure.test.check.generators
  - clojure.test.check.properties

### Fixed / enhanced

- Fix symbol resolution in syntax quote when overwriting core var
- Performance enhancements

## v0.2.7

### New

- Add Alpine [Docker images](https://hub.docker.com/repository/registry-1.docker.io/babashka/babashka/tags?page=1&ordering=last_updated) [#699](https://github.com/babashka/babashka/issues/699) ([@lispyclouds](https://github.com/lispyclouds))
- Add `pp` from `clojure.pprint` [#707](https://github.com/babashka/babashka/issues/707)

### Fixed / enhanced

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

### v1.1.172

- Bump `babashka.process`: change default for `:out :append` to `:out :write`. This default is undocumented so the impact should be small.

### v1.1.171

- [#1467](https://github.com/babashka/babashka/issues/1467): avoid printing results, unless `--prn` is enabled (aside from `-e`, `-o` and `-O`).

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
