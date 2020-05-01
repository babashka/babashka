# Building babashka

## Prerequisites

- Install [lein](https://leiningen.org/) for producing uberjars
- Download [GraalVM](https://www.graalvm.org/downloads/). Currently we use *java8-19.3.1*.
- Set `$GRAALVM_HOME` to the GraalVM distribution directory. On macOS this can look like:

  ``` shell
  export GRAALVM_HOME=~/Downloads/graalvm-ce-java8-19.3.1/Contents/Home
  ```

  On linux:

  ``` shell
  export GRAALVM_HOME=~/Downloads/graalvm-ce-java8-19.3.1
  ```

  On Windows:
  ```
  set GRAALVM_HOME=C:\Users\IEUser\Downloads\graalvm-ce-java8-19.3.1
  ```

## Clone repository

NOTE: the babashka repository contains submodules. You need to use the
`--recursive` flag to clone these submodules along with the main repo.

``` shellsession
$ git clone https://github.com/borkdude/babashka --recursive
```

To update later on:

``` shellsession
$ git submodule update --recursive
```

## Build

Run the `uberjar` and `compile` script:

``` shell
$ script/uberjar
$ script/compile
```

To configure maximum heap size you can use:

```
$ export BABASHKA_XMX="-J-Xmx4800m"
```

## Windows

To compile on Windows you need to check out the `windows` branch:

``` shell
$ git checkout windows
```

Then run `script\uberjar.bat` followed by `script\compile.bat`.

## Feature flags

Babashka supports the following feature flags:

| Name   |  Description                                 | Default  |
|--------|----------------------------------------------|----------|
| `BABASHKA_FEATURE_CORE_ASYNC` | Includes the [clojure.core.async](https://github.com/clojure/core.async) library | `true` |
| `BABASHKA_FEATURE_CSV` | Includes the [clojure.data.csv](https://github.com/clojure/data.csv) library | `true` |
| `BABASHKA_FEATURE_JAVA_NIO` | Includes commonly used classes from the `java.nio` package | `true` |
| `BABASHKA_FEATURE_JAVA_TIME` | Includes commonly used classes from the `java.time` package | `true` |
| `BABASHKA_FEATURE_TRANSIT` | Includes the [transit-clj](https://github.com/cognitect/transit-clj) library | `true` |
| `BABASHKA_FEATURE_XML` | Includes the [clojure.data.xml](https://github.com/clojure/data.xml) library | `true` |
| `BABASHKA_FEATURE_YAML` | Includes the [clj-yaml](https://github.com/clj-commons/clj-yaml) library | `true` |
| `BABASHKA_FEATURE_JDBC` | Includes the [next.jdbc](https://github.com/seancorfield/next-jdbc) library | `false`    |
| `BABASHKA_FEATURE_POSTGRESQL` | Includes the [PostgresSQL](https://jdbc.postgresql.org/) JDBC driver |  `false` |
| `BABASHKA_FEATURE_HSQLDB` | Includes the [HSQLDB](http://www.hsqldb.org/) JDBC driver | `false` |
| `BABASHKA_FEATURE_DATASCRIPT` | Includes [datascript](https://github.com/tonsky/datascript) | `false` |

To disable all of the above features, you can set `BABASHKA_LEAN` to `true`.

### HyperSQL

To compile babashka with the `next.jdbc` library and the embedded HyperSQL
database:

``` shell
$ export BABASHKA_FEATURE_JDBC=true
$ export BABASHKA_FEATURE_HSQLDB=true
$ script/uberjar
$ script/compile
```

Check out this [example](examples.md#find-unused-vars).

### PostgresQL

To compile babashka with the `next.jdbc` library and a PostgresQL driver:

``` shell
$ export BABASHKA_FEATURE_JDBC=true
$ export BABASHKA_FEATURE_POSTGRESQL=true
$ script/uberjar
$ script/compile
```
