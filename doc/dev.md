# Developing Babashka

To work on Babashka itself make sure Git submodules are checked out.

``` shellsession
$ git clone https://github.com/borkdude/babashka --recursive
```

To update later on:

``` shellsession
$ git submodule update --recursive
```

You need [Leiningen](https://leiningen.org/), and for building binaries you need GraalVM.

## REPL

`lein repl` will get you a standard REPL/nREPL connection. To work on tests use `lein with-profiles +test repl`.

### Adding classes

Add necessary classes to `babashka/impl/classes.clj`.  For every addition, write
a unit test, so it's clear why it is added and removing it will break the
tests. Try to reduce the size of the binary by only adding the necessary parts
of a class in `:instance-check`, `:constructors`, `:methods`, `:fields` or
`:custom`.

The `reflection.json` file that is needed for GraalVM compilation is generated
with:

    lein with-profiles +reflection run

## Test

Test on the JVM (for development):

    script/test

Test the native version:

    BABASHKA_TEST_ENV=native script/test

## Build

To build this project, set `$GRAALVM_HOME` to the GraalVM distribution directory. Currently we are using GraalVM JDK8.

Then run:

    script/compile

## Binary size

Keep notes here about how adding libraries and classes to Babashka affects the binary size.

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
