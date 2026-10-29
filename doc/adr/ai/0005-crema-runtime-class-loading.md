# ADR 0005: keep Crema out of bb

## Status

Recorded 2026-10-29. Rejected for now. The experiment code is on the
unmerged branch `crema-hybrid`. Earlier Crema work for deftype with JVM
interfaces is in sci's ADR 0016 and on bb's branch `crema-deftype`.

## Context

GraalVM 25.1 ships Crema (`-H:+RuntimeClassLoading`). A native image built
with it can define classes at run time and run them in a bytecode
interpreter. Two things become possible:

- bb could load Java classes from jars on the classpath, which it cannot
  do today.
- Full JVM Clojure can run in a native binary. cream
  (https://github.com/borkdude/cream) does this with a Clojure fork.

The question was whether bb should adopt Crema, or whether a hybrid of bb
and cream gives the benefits of both without a large binary.

## The experiment

Branch `crema-hybrid` adds `babashka.classpath/load-class`. It loads a
class from a jar through `babashka.impl.JarClassLoader` and registers it
with `sci/add-class!`, after which SCI interop calls it like any built-in
class. Build it with `BABASHKA_CREMA=true script/compile`.

The loader is child-first for classes found in jars. bb's image already
contains some library classes, such as commonmark and jsoup, without
reflection metadata. A parent-first loader returns that image copy and
interop on it fails.

## Binary size

Built from master `401983b7` with `BABASHKA_LIBFFI=none` and cream's
`-H:Preserve` package list.

| GraalVM | bb | bb + Crema | build secs |
|---|---|---|---|
| 25.1.3 | 73.8 MB | 203.6 MB | 40 / 64 |
| 25.3.4.1-dev | 72.3 MB | 181.4 MB | 39 / 63 |

The extra size is interpreter metadata for every type and method in the
image. No native-image option restricts it to selected packages.

GraalVM 25.0.4 builds with Crema at a lower cost, 144.6 MB on a 104.9 MB
base, but reflection on run-time loaded classes is unfinished there
("enclosing class is not supported yet"). It cannot run these libraries.

Pruning unused vars with `NamespaceFeature` (branch `spec-dynaload-exp`)
was not combined with Crema. The image size fix in `401983b7` already
gives the size that feature reached, so little gain is expected.

## What works

These libraries need GraalVM 25.1.3 or later. Crema in GraalVM 25.0.4
cannot reflect on classes loaded at run time.

Startup stays at 12 to 23 ms with and without Crema.

commons-codec, commons-lang3, jsoup, commonmark, gson and joda-time load
and run. Caffeine works only with a `^Cache` type hint. Without it SCI
fails with "Can't call public method of non-public class".

Java code in the Crema interpreter runs slower than on the JVM. 100k
calls to `DigestUtils/sha256Hex`:

| runtime | ms |
|---|---|
| bb + Crema, GraalVM 25.1.3 | 670 to 1800 |
| bb + Crema, GraalVM 25.3.4.1-dev | 260 to 600 |
| cream, JIT on | 124 |
| JVM | 42 |

## Why it was rejected

bb is a scripting tool. A script starts, loads its libraries, does a
short job and exits. Startup and load time come first, run-time
performance after that.

- Every class Crema loads is defined at run time and runs in an
  interpreter. Startup stays the same, but the bigger the library, the
  longer the load. cream shows the cost for Clojure code: malli loads in
  ~1300 ms in cream and in ~60 ms in bb, see below.
- A package missing from `-H:Preserve` aborts the whole process instead
  of throwing an exception. Which packages a library needs is only found
  by running it, and the user's script dies with no stack trace to act
  on. Seen:
  - `Unable to call AOT method ... Enum.<init>` without `java.lang`.
  - `Cannot load undefined field ... Void.TYPE` in Caffeine.
  - `NoClassDefFoundError Ljava/lang/invoke/LambdaMetafactory` without
    `java.lang.invoke`.
  - gson aborts on `AtomicIntegerArray` with only `java.lang`,
    `java.lang.invoke`, `java.util` and `java.util.function` preserved.
- The native build takes about 1.6 times longer, 40 s against 64 s
  locally, and every CI platform pays that. The binary grows from 74 MB
  to 180 to 200 MB for a feature most scripts do not use.

## bb compared with cream

cream runs JVM Clojure, so every library loads. It compiles every
function to a class at load time.

| | cream | bb |
|---|---|---|
| Startup | ~10 ms | ~10 ms |
| `(require 'malli.core 'malli.generator)` | ~1300 ms | ~60 ms |
| malli validate, 1M calls | 70 ms | 388 ms |
| fib 27, cold | 43 ms | 25 ms |
| 100 x `(eval '(fn [] ...))` | 38 ms | 0.5 ms |
| Binary size | ~224 MB | ~74 MB |

Defining and linking a class costs about 0.2 ms in cream, against 0.05 ms
on the JVM. malli defines about 1670 classes.

cream loads malli in ~330 ms from AOT-compiled classes. A transparent
class cache works without changes to the Clojure fork: bind
`*compile-files*` to true and `*compile-path*` to a cache directory placed
first on the classpath. The first run takes 1.2 s, later runs 0.33 to
0.38 s. It was not pursued, because scripts should not depend on a cache
that goes stale or must be managed.

Scripts mostly load code and run it briefly. bb loads code 10 to 20 times
faster than cream. cream runs hot code faster after JIT compilation. For
scripting, load time decides.

## Decision

bb stays without Crema. Java libraries that bb does not include are
reached through pods or through cream.

Revisit when a GraalVM release reduces the Crema metadata or allows
restricting it to selected packages, and when a missing preserve throws
instead of aborting.
