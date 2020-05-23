# Developing Babashka

You need [lein](https://leiningen.org/) for running JVM tests and/or producing uberjars. For building binaries you need GraalVM. Currently we use java11-20.1.0.

## Clone repository

To work on Babashka itself make sure Git submodules are checked out.

``` shellsession
$ git clone https://github.com/borkdude/babashka --recursive
```

To update later on:

``` shellsession
$ git submodule update --recursive
```

## REPL

`lein repl` will get you a standard REPL/nREPL connection. To work on tests use `lein with-profiles +test repl`.

## Adding classes

Add necessary classes to `babashka/impl/classes.clj`.  For every addition, write
a unit test, so it's clear why it is added and removing it will break the
tests. Try to reduce the size of the binary by only adding the necessary parts
of a class in `:instance-check`, `:constructors`, `:methods`, `:fields` or
`:custom`.

The `reflection.json` file that is needed for GraalVM compilation is generated
as part of `script/uberjar`.

## Test

Test on the JVM (for development):

    script/test

Test the native version:

    BABASHKA_TEST_ENV=native script/test

## Build

See [build.md](build.md).

## JDBC

Findings from various experiments with JDBC drivers in babashka:

- Postgres: adds 3MB to the binary. It seems the maintainers have put in effort
  to make the driver compatible with Graal. The driver is part of `bb` since
  `v0.0.89`.
- Sqlite: I feel like I'm close to a working solution, but it hangs. It adds
  20MB to the binary. Since sqlite has a nice CLI we could also just shell out
  to it (there's an example in the examples dir). We could also build a
  `babashka.sqlite` namespace around the CLI maybe similar to
  `babashka.curl`. See [#385](https://github.com/borkdude/babashka/issues/385)
  for details.
- HSQLDB: easy to get going with Graalvm. Adds 10 MB to the binary. It's under a
  feature flag right now on master. See [build.md](build.md) for details. Derby
  and H2 are known to not work with GraalVM, so far this is the "best" embedded
  option from a Graal perspective.  Setting the -Xmx value for Docker to 4500m
  got it to crash. 4800m did work, but it took 17 minutes (compared to 10
  minutes without this feature).
- MySQL / MariaDB: can't get those to work yet. Work in progress in issue
  [#387](https://github.com/borkdude/babashka/issues/387).

To progress work on sqlite and mySQL, I need a working Clojure example. If you
want to contribute, consider making a an example Clojure GraalVM CLI that puts
something in a sqlite / mysql DB and reads something from it.

## Binary size

Keep notes here about how adding libraries and classes to Babashka affects the binary size.
We're registering the size of the macOS binary (as built on CircleCI).

2020/05/01 Removed `next.jdbc` and postgres JDBC driver: 48304980

2020/04/23 Added `next.jdbc` and postgres JDBC driver:
(- 51019836 48099780) = 2920kb added

2020/04/23 Added BigDecimal
(- 48103868 47857732) = 246kb added

2020/04/18 Added clojure.data.xml
47808572 - 45923028 = 1886kb added.

2020/03/29 Added clj-yaml for parsing and generating yaml.
45196996 - 42626884 = 2570kb added.

2020/03/28 Added java.nio.file.FileSystem(s) to support extracting zip files
42562284 - 42021244 = 541kb added.

2020/03/22 Added java.io.FileReader
42025276 - 42008876 = 16kb added.

2020/03/20 Added transit write, writer, read, reader
42004796 - 41025212 = 980kb added (305kb zipped).

2020/03/19 Added java.lang.NumberFormatException, java.lang.RuntimeException,
java.util.MissingResourceException and java.util.Properties to support
[cprop](https://github.com/tolitius/cprop/).
41025180 - 40729908 = 295kb added.

2020/02/21
Added java.time.temporal.ChronoUnit
40651596 - 40598260 = 53kb added.

2020/02/19, e43727955a2cdabd2bb0189c20dd7f9a18156fc9
Added fipp.edn/pprint
40598268 - 39744804 = 853kb added.

2020/02/09, c8fd1c7931d7842ebaec1fa8faf06d4ab58573bd
Added java.lang.BigInteger and java.security.MessageDigest.
39281972 - 39072764 = 209kb added.

2020/04/02 v0.0.69 38883676

2020/01/24, 43eef7075f9dac038d8d28a5ee4e49b6affd9864: 38.3mb, 11.1mb zipped
Added hierarchies (derive, isa?, etc).

2020/01/23, 485fef7df54d6701936704573468a1ec4c66d221: 37.4mb / 10.9mb zipped
Added: StringBuilder, java.io.{Reader,Writer,PrinterWriter,PushbackReader}

2020/01/08, 303ca9e825d76a4a45bc4240a59139d342c13964: 36.9mb / 10.8mb zipped

Removing cheshire from bb: 36.2mb / 10.5mb zipped.
