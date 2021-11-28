# Building babashka

## Prerequisites

- Install [lein](https://leiningen.org/) for producing uberjars
- Download [GraalVM](https://www.graalvm.org/downloads/). Currently we use *java11-21.3.0*.
- For Windows, installing Visual Studio 2019 with the "Desktop development
with C++" workload is recommended.
- Set `$GRAALVM_HOME` to the GraalVM distribution directory. On macOS this can look like:

  ``` shell
  export GRAALVM_HOME=~/Downloads/graalvm-ce-java11-21.3.0/Contents/Home
  ```

  On linux:

  ``` shell
  export GRAALVM_HOME=~/Downloads/graalvm-ce-java11-21.3.0
  ```

  On Windows, from the [Visual Studio 2019 x64 Native Tools Command Prompt](https://github.com/oracle/graal/issues/2116#issuecomment-590470806) or `cmd.exe` (not Powershell):
  ```
  set GRAALVM_HOME=%USERPROFILE%\Downloads\graalvm-ce-java11-21.3.0
  ```
  If you are not running from the x64 Native Tools Command Prompt, you will need to set additional environment variables using:
  ```
  call "C:\Program Files (x86)\Microsoft Visual Studio\2019\Community\VC\Auxiliary\Build\vcvars64.bat"
  ```

## Clone repository

NOTE: the babashka repository contains submodules. You need to use the
`--recursive` flag to clone these submodules along with the main repo.

``` shellsession
$ git clone https://github.com/babashka/babashka --recursive
```

To update later on:

``` shellsession
$ git submodule update --init --recursive
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

### Alternative: Build inside Docker

To build a Linux version of babashka, you can use `docker build`, enabling the 
desired features via `--build-arg` like this:

```shell
docker build --build-arg BABASHKA_FEATURE_JDBC=true --target BASE -t bb-builder .
container_id=$(docker create bb-builder)
docker cp $container_id:/opt/bb bb # copy to ./bb on the host file system
docker rm $container_id
```

NOTE: If you get _Error: Image build request failed with exit status 137_ then
check whether Docker is allowed to use enough memory (e.g. in Docker Desktop
preferences). If it is, then increase the memory GraalVM can use, for example
by adding `--build-arg BABASHKA_XMX="-J-Xmx8g"`
(or whatever Docker has available, bigger than the default).

## Windows

Run `script\uberjar.bat` followed by `script\compile.bat`.

## Static

To compile babashka as a static binary for linux, set the `BABASHKA_STATIC`
environment variable to `true`.

## Feature flags

Babashka supports the following feature flags:

| Name   |  Description                                 | Default  |
|--------|----------------------------------------------|----------|
| `BABASHKA_FEATURE_CSV` | Includes the [clojure.data.csv](https://github.com/clojure/data.csv) library | `true` |
| `BABASHKA_FEATURE_JAVA_NET_HTTP` | Includes commonly used classes from the `java.net.http` package | `true` |
| `BABASHKA_FEATURE_JAVA_NIO` | Includes commonly used classes from the `java.nio` package | `true` |
| `BABASHKA_FEATURE_JAVA_TIME` | Includes commonly used classes from the `java.time` package | `true` |
| `BABASHKA_FEATURE_TRANSIT` | Includes the [transit-clj](https://github.com/cognitect/transit-clj) library | `true` |
| `BABASHKA_FEATURE_XML` | Includes the [clojure.data.xml](https://github.com/clojure/data.xml) library | `true` |
| `BABASHKA_FEATURE_YAML` | Includes the [clj-yaml](https://github.com/clj-commons/clj-yaml) library | `true` |
| `BABASHKA_FEATURE_HTTPKIT_CLIENT` | Includes the [http-kit](https://github.com/http-kit/http-kit) client library | `true` |
| `BABASHKA_FEATURE_HTTPKIT_SERVER` | Includes the [http-kit](https://github.com/http-kit/http-kit) server library | `true` |
| `BABASHKA_FEATURE_CORE_MATCH` | Includes the [clojure.core.match](https://github.com/clojure/core.match) library | `true` |
| `BABASHKA_FEATURE_HICCUP` | Includes the [hiccup](https://github.com/weavejester/hiccup) library | `true` |
| `BABASHKA_FEATURE_TEST_CHECK` | Includes the [clojure.test.check](https://github.com/clojure/test.check) library | `true` |
| `BABASHKA_FEATURE_SPEC_ALPHA` | Includes the [clojure.spec.alpha](https://github.com/clojure/spec.alpha) library (WIP) | `false` |
| `BABASHKA_FEATURE_JDBC` | Includes the [next.jdbc](https://github.com/seancorfield/next-jdbc) library | `false`    |
| `BABASHKA_FEATURE_SQLITE` | Includes the [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) library | `false`    |
| `BABASHKA_FEATURE_POSTGRESQL` | Includes the [PostgresSQL](https://jdbc.postgresql.org/) JDBC driver |  `false` |
| `BABASHKA_FEATURE_HSQLDB` | Includes the [HSQLDB](http://www.hsqldb.org/) JDBC driver | `false` |
| `BABASHKA_FEATURE_ORACLEDB` | Includes the [Oracle](https://www.oracle.com/database/technologies/appdev/jdbc.html) JDBC driver | `false` |
| `BABASHKA_FEATURE_DATASCRIPT` | Includes [datascript](https://github.com/tonsky/datascript) | `false` |
| `BABASHKA_FEATURE_LANTERNA` | Includes [clojure-lanterna](https://github.com/babashka/clojure-lanterna) | `false` |
| `BABASHKA_FEATURE_LOGGING` | Includes [clojure.tools.logging](https://github.com/clojure/tools.logging) with [taoensso.timbre](https://github.com/ptaoussanis/timbre) as the default implementation| `true` | 

Note that httpkit server is currently experimental, the feature flag could be toggled to `false` in a future release.

To disable all of the above features, you can set `BABASHKA_LEAN` to `true`.

Here is an [example
commit](https://github.com/babashka/babashka/commit/13f65f05aeff891678e88965d9fbd146bfa87f4e)
that can be used as a checklist when you want to create a new feature flag.

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

### Lanterna

To compile babashka with the [babashka/clojure-lanterna](https://github.com/babashka/clojure-lanterna) library:

``` shell
$ export BABASHKA_FEATURE_LANTERNA=true
$ script/uberjar
$ script/compile
```

Example program:

``` clojure
(require '[lanterna.terminal :as terminal])

(def terminal (terminal/get-terminal))

(terminal/start terminal)
(terminal/put-string terminal "Hello TUI Babashka!" 10 5)
(terminal/flush terminal)

(read-line)
```
