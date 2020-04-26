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

Run the `compile` script:

``` shell
$ script/compile
```

To configure maximum heap size you can use:

```
$ BABASHKA_XMX="-J-Xmx4g" script/compile
```

## Windows

To compile on Windows you need to check out the `windows` branch:

``` shell
$ git checkout windows
```

The compile script for Windows is `script/compile.bat`.

## Optional features

### HyperSQL

To compile babashka with `HyperSQL`/`hsqldb` support, set
`BABASHKA_FEATURE_HSQLDB` to `true`:

``` shell
$ BABASHKA_FEATURE_HSQLDB=true script/compile
```

Check out this [example](examples.md#find-unused-vars).

If you think this feature should be enabled in the distributed version of `bb`,
vote with a thumbs up on [this](https://github.com/borkdude/babashka/issues/382)
issue.
