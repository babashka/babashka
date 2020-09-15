# Building babashka

## Prerequisites

- Install [lein](https://leiningen.org/) for producing uberjars
- Download [GraalVM](https://www.graalvm.org/downloads/). Currently we use *java11-20.1.0*.
- For Windows, installing Visual Studio Code 2019 with the "Desktop development
with C++" workload is recommended.
- Set `$GRAALVM_HOME` to the GraalVM distribution directory. On macOS this can look like:

  ``` shell
  export GRAALVM_HOME=~/Downloads/graalvm-ce-java11-20.1.0/Contents/Home
  ```

  On linux:

  ``` shell
  export GRAALVM_HOME=~/Downloads/graalvm-ce-java11-20.1.0
  ```

  On Windows, from the Visual Studio 2019 Developer Command Prompt or `cmd.exe` (not Powershell):
  ```
  set GRAALVM_HOME=%USERPROFILE%\Downloads\graalvm-ce-java11-20.1.0
  ```
  If you are not running from the Developer Command Prompt, you will need to set additional environment variables using:
  ```
  call "C:\Program Files (x86)\Microsoft Visual Studio\2019\Community\VC\Auxiliary\Build\vcvars64.bat"
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

To configure max heap size you can use:

```
$ export BABASHKA_XMX="-J-Xmx6500m"
```

Note: setting the max heap size to a low value can cause the build to crash or
take long to complete.

To build on Windows, run `script\uberjar.bat` followed by `script\compile.bat`.

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

Note: there is now a [pod](https://github.com/babashka/babashka-sql-pods) for working with HyperSQL.

### PostgresQL

To compile babashka with the `next.jdbc` library and a PostgresQL driver:

``` shell
$ export BABASHKA_FEATURE_JDBC=true
$ export BABASHKA_FEATURE_POSTGRESQL=true
$ script/uberjar
$ script/compile
```

Note: there is now a [pod](https://github.com/babashka/babashka-sql-pods) for working with PostgreSQL.
