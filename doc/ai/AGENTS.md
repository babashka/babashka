# AGENTS.md

Guidance for coding agents working in this repository.

## Project Overview

Babashka is a native Clojure interpreter for scripting with fast startup time. It uses GraalVM to compile to a native binary, designed for scripting tasks where you'd normally use bash but want Clojure's power.

## Build Commands

```bash
# Build uberjar (required before native compilation)
script/uberjar

# Compile to native binary (requires GraalVM, set GRAALVM_HOME)
script/compile

# Run tests on JVM
script/test

# Run tests against native binary
BABASHKA_TEST_ENV=native script/test

# Run library compatibility tests
script/run_lib_tests

# Windows variants
script\uberjar.bat
script\compile.bat
script\test.bat
```

## Development

```bash
# Start REPL
lein repl

# Start REPL with test profile
lein with-profiles +test repl

# Run specific test namespace
lein test :only babashka.main-test

# Run specific test
lein test :only babashka.main-test/some-test-name
```

## Repository Structure

This repo uses git submodules. Clone with `--recursive` or run `git submodule update --init --recursive`.

**Key source paths:**
- `src/babashka/` - Main babashka implementation
- `src/babashka/impl/` - Implementation details (classes, features, sci integration)
- `sci/` - SCI (Small Clojure Interpreter) submodule - the core interpreter
- `resources/src/babashka/` - sources bundled into the binary and loaded by bb itself, vendored by `script/vendor_bundled_sources.clj` (nREPL, tools.deps, the Maven layer, orchard)
- `feature-*/` - Optional feature modules (xml, yaml, jdbc, etc.)

**Key files:**
- `src/babashka/main.clj` - Main entry point
- `src/babashka/impl/classes.clj` - Java class definitions for GraalVM reflection
- `project.clj` - Leiningen configuration with feature profiles
- `resources/BABASHKA_VERSION` - Current version

## Tests

Add tests to the suites below. Prefer them over a new standalone runner:
discovery, reporting and CI wiring already exist.

### babashka's own tests

`script/test` runs the Leiningen suite in `test/babashka/`. It is the JVM
route by default and runs against the built binary with
`BABASHKA_TEST_ENV=native`. `test/babashka/test_utils.clj` decides which,
so the same test covers both. Always prefix Leiningen tasks with `clean`.

Selectors: `:default` (all but Windows-only and flaky), `:windows`,
`:flaky` (run separately), `:non-flaky`.

### Library compatibility tests

`script/run_lib_tests` runs other projects' test suites against bb. The
registry is `test-resources/lib_tests/bb-tested-libs.edn`, one entry per
library with `:git-url`, `:git-sha` and `:test-namespaces`, plus optional
`:test-paths`, `:skip-windows` and `:flaky`. The driver is
`test-resources/lib_tests/babashka/run_all_libtests.clj` and the classpath
comes from the `:lib-tests` alias in `deps.edn`.

An entry gets its test sources one of two ways. With `:test-paths` the
driver clones the library at the pinned sha into `~/.gitlibs` and adds those
paths to the classpath, which is preferred because nothing is copied. Older
entries instead keep a copy of the test files under
`test-resources/lib_tests/<lib>/`, written by `script/add_libtest.clj` and
edited in place, where every deviation from upstream carries a
`BB-TEST-PATCH` comment.

To skip a single upstream test rather than a whole namespace, give its var
`:skip-bb` metadata in the driver. The driver drops vars marked `:skip-bb`,
`:integration`, `:test-check-slow` or `:flaky`.

A library whose tests need their own working directory, extra dependencies
or a spawned process gets a block at the end of the driver, after the
standard run. Clerk and nREPL are the worked examples.

### Maven layer tests

`script/mvn_oracle/tests.clj` runs every `*_test.clj` next to it, each as
its own bb process with its own exit code. Without arguments the tests load
the Maven sources from the tree; with `--image` they run against the
sources bundled in `./bb`, which is what CI does. Test cases ported from
Maven, the resolver and plexus live here.

`script/mvn_oracle/run.clj` compares bb's resolution against tools.deps over
a corpus. It needs the network, so it is run by hand after changes to the
procurer, and every difference it turns up becomes a case in a test script.

### nREPL's own test suite

`script/nrepl_tests.clj` runs nREPL's upstream tests against the bundled
server. It reads the pinned sha from `bb-tested-libs.edn`, clones into
`~/.gitlibs`, rewrites the few forms sci cannot load, and skips tests that
need a JVM-only facility. The lib tests driver runs it as one of its
non-standard blocks, against the built binary, so it is part of
`script/run_lib_tests` on Linux and macOS.

Run it alone with `./bb script/nrepl_tests.clj [namespace ...]`, which is
what to do when bundled nREPL is bumped and the patches in
`script/vendor_bundled_sources.clj` are re-applied.

### CI

`.github/workflows/build.yml` runs `script/test` and `script/run_lib_tests`
on the JVM stage, and after the native build runs both again plus
`./bb script/mvn_oracle/tests.clj --image`. `build-windows.yml` is the
Windows equivalent. Both trigger only on pushes to master and on pull
requests against master, so pushing a branch runs no CI.

## Feature System

Babashka has optional features controlled by environment variables during build:
- Default (enabled): CSV, XML, YAML, Transit, Java Time, java.net.http, Java NIO, HTTP Kit client and server, core.match, Hiccup, test.check, Selmer, Logging, Priority Map
- Opt-in (disabled): JDBC, SQLite, PostgreSQL, Oracle DB, HSQLDB, DataScript, Lanterna, Spec Alpha, RRB Vector, libffi

The flags themselves live in `src/babashka/impl/features.clj`, one var per
feature, and that file is the list to trust.

Example: `BABASHKA_FEATURE_JDBC=true script/uberjar`

Set `BABASHKA_LEAN=true` to disable all optional features.

## Adding Java Classes

1. Add class entries to `src/babashka/impl/classes.clj`
2. Only add necessary parts: `:instance-check`, `:constructors`, `:methods`, `:fields`, or `:custom`
3. Write unit tests for additions
4. `reflection.json` is auto-generated during `script/uberjar`

Reflection that works on the JVM can still fail in the image. Reading or
writing a private field needs that field registered, and the failure is a
`MissingReflectionRegistrationError` at run time, so exercise the path with
`BABASHKA_TEST_ENV=native` or through `script/mvn_oracle/tests.clj --image`.

## Build Requirements

- Leiningen 2.9.8+
- Oracle GraalVM 25 (set `GRAALVM_HOME`)
- Java 21+
- For Windows: Visual Studio 2019 with C++ workload
- For Linux static builds: musl and zlib

## Useful Environment Variables

- `GRAALVM_HOME` - GraalVM installation directory
- `BABASHKA_XMX` - JVM heap size for compilation (e.g., `-J-Xmx6500m`)
- `BABASHKA_STATIC=true` - Build static binary (Linux)
- `BABASHKA_TEST_ENV=native` - Test native binary instead of JVM

# Clojure REPL Evaluation

The command `clj-nrepl-eval` is installed on your path for evaluating Clojure code via nREPL.

**Discover nREPL servers:**

`clj-nrepl-eval --discover-ports`

**Evaluate code:**

`clj-nrepl-eval -p <port> "<clojure-code>"`

With timeout (milliseconds)

`clj-nrepl-eval -p <port> --timeout 5000 "<clojure-code>"`

The REPL session persists between evaluations - namespaces and state are maintained.
Always use `:reload` when requiring namespaces to pick up changes.
