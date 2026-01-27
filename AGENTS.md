# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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
- `feature-*/` - Optional feature modules (xml, yaml, jdbc, etc.)

**Key files:**
- `src/babashka/main.clj` - Main entry point
- `src/babashka/impl/classes.clj` - Java class definitions for GraalVM reflection
- `project.clj` - Leiningen configuration with feature profiles
- `resources/BABASHKA_VERSION` - Current version

## Feature System

Babashka has optional features controlled by environment variables during build:
- Default (enabled): CSV, XML, YAML, Transit, Java Time, Java NIO, HTTP Kit, Core Match, Hiccup, Test Check, Selmer, Logging, Priority Map
- Opt-in (disabled): JDBC, SQLite, PostgreSQL, Oracle DB, HSQLDB, DataScript, Lanterna, Spec Alpha

Example: `BABASHKA_FEATURE_JDBC=true script/uberjar`

Set `BABASHKA_LEAN=true` to disable all optional features.

## Adding Java Classes

1. Add class entries to `src/babashka/impl/classes.clj`
2. Only add necessary parts: `:instance-check`, `:constructors`, `:methods`, `:fields`, or `:custom`
3. Write unit tests for additions
4. `reflection.json` is auto-generated during `script/uberjar`

## Test Selectors

- `:default` - All tests except Windows-only and flaky
- `:windows` - Windows platform tests
- `:flaky` - Flaky tests (run separately)
- `:non-flaky` - All non-flaky tests

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
