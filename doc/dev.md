# Developing Babashka

## Workflow

### Start with an issue before writing code

Before writing any code, please create an issue first that describes the problem
you are trying to solve with alternatives that you have considered. A little bit
of prior communication can save a lot of time on coding. Keep the problem as
small as possible. If there are two problems, make two issues. We discuss the
issue and if we reach an agreement on the approach, it's time to move on to a
PR.

### Follow up with a pull request

Post a corresponding PR with the smallest change possible to address the
issue. Then we discuss the PR, make changes as needed and if we reach an
agreement, the PR will be merged.

### Tests

Each bug fix, change or new feature should be tested well to prevent future
regressions.

### Force-push

Please do not use `git push --force` on your PR branch for the following
reasons:

- It makes it more difficult for others to contribute to your branch if needed.
- It makes it harder to review incremental commits.
- Links (in e.g. e-mails and notifications) go stale and you're confronted with:
  this code isn't here anymore, when clicking on them.
- CircleCI doesn't play well with it: it might try to fetch a commit which
  doesn't exist anymore.
- Your PR will be squashed anyway.

## Requirements

You need [lein](https://leiningen.org/) for running JVM tests and/or producing uberjars. For building binaries you need GraalVM. Currently we use java11-22.1.0.

## Clone repository

To work on Babashka itself make sure Git submodules are checked out.

``` shellsession
$ git clone https://github.com/babashka/babashka --recursive
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

## Tests for Libraries

Babashka runs tests of libraries that are compatible with it through
`script/run_lib_tests`. The script `add-libtest.clj` makes adding new libraries
fairly easy. Some examples:

```sh
# To add tests for a new library on clojars
script/add-libtest.clj com.exoscale/lingo -t

# To add tests for a new library that is git based only
script/add-libtest.clj '{borkdude/carve {:git/url "https://github.com/borkdude/carve" :sha "df552797a198b6701fb2d92390fce7c59205ea77"}}' -t

# There are a number of options for specifying how to copy tests
script/add-libtest.clj -h
```

If the library you want to add doesn't work automatically, you can manually do the following:

* Add an entry for the library in `deps.edn` under the `:lib-tests` alias.
* Create a directory for the library in `test-resources/lib_tests/` and copy its tests to there.
* Add a manual lib entry using `add-libtest.clj` e.g. `script/add-libtest.clj http-kit/http-kit -m '{:test-namespaces [httpkit.client-test]}'`.
* Run the tests `script/lib_tests/run_all_libtests NS1 NS2`

Note: If you have to modify any test file or configuration to have it work with
bb, add an inline comment with prefix `BB-TEST-PATCH:` explaining what you did.

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
  `babashka.curl`. See [#385](https://github.com/babashka/babashka/issues/385)
  for details.
- HSQLDB: easy to get going with Graalvm. Adds 10 MB to the binary. It's under a
  feature flag right now on master. See [build.md](build.md) for details. Derby
  and H2 are known to not work with GraalVM, so far this is the "best" embedded
  option from a Graal perspective.  Setting the -Xmx value for Docker to 4500m
  got it to crash. 4800m did work, but it took 17 minutes (compared to 10
  minutes without this feature).
- MySQL / MariaDB: can't get those to work yet. Work in progress in issue
  [#387](https://github.com/babashka/babashka/issues/387).

To progress work on sqlite and mySQL, I need a working Clojure example. If you
want to contribute, consider making a an example Clojure GraalVM CLI that puts
something in a sqlite / mysql DB and reads something from it.

## Design decisions

Some design decisions:

### bb.edn

- We chose the name `bb.edn` (rather than `babashka.edn`) for the configuration
  file based on this
  [poll](https://twitter.com/borkdude/status/1374720217608302595). The name `bb`
  combined with `.edn` is not likely to cause conflicts with other tools.
- We did not choose to put the babashka configuration in `deps.edn` to keep bb config isolated (and more flexible) and also support it in projects that do not use `deps.edn`

### .babashka

- Rather than naming the home config dir `~/.bb` we chose `~/.babashka` to
  prevent conflicts with other global tools. We might introduce a project local
  `~/.babashka` directory for storing caches or whatnot too.

### Tasks

Some of these design decisions were formed in [these discussions](https://github.com/babashka/babashka/discussions/779).

- Tasks do not allow passing arguments to dependent tasks, other than by rebinding `*command-line-args*` (see discussion).
- Does the list of dependencies need to be dynamic? No, see discussion (same reason as args)
- bb &lt;foo&gt; is resolved as file > task > bb subcommand. Shadowing future subcommand is a problem that a user can solve by renaming a task or file. (same as lein aliases). Also see Conflicts.
- It is a feature that tasks are defined as top-level vars (instead of local let-bound symbols). This plays well with the Gilardi scenario, e.g. here: https://github.com/babashka/babashka.github.io/blob/ad276625f6c41f269d19450f236cb54cab2591e1/bb.edn#L7.
- The parallel option trickles down into run calls. People who use parallel will be confused if it’s dropped magically, people who don’t use parallel won’t notice anything either way so it doesn’t matter

## Binary size

Keep notes here about how adding libraries and classes to Babashka affects the binary size.
We're registering the size of the macOS binary (as built on CircleCI).

2021/06/13 Upgrading from GraalvM 21.0 to 21.1 added roughly 3mb. Issue [here](https://github.com/oracle/graal/issues/3280#issuecomment-846402115).

2020/10/30 Without httpkit client+server: 68113436. With: 69503316 = 1390kb added.

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
