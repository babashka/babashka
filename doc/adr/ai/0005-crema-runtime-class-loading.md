# ADR 0005: keep Crema out of the default bb build

## Status

Recorded 2026-10-29 on branch `crema-hybrid`.

## Context

GraalVM's Crema (`-H:+RuntimeClassLoading`) lets a native image define
classes at run time and run them in an interpreter. With it, bb can load
Java classes from jars on the classpath and call them through SCI interop.
cream (https://github.com/borkdude/cream) uses the same mechanism to run
JVM Clojure in a native binary.

Branch `crema-hybrid` adds `babashka.classpath/load-class`. It loads a
class through `babashka.impl.JarClassLoader` and registers it with
`sci/add-class!`. The loader is child-first for jar classes, because bb's
image already contains classes such as commonmark and jsoup without
reflection metadata, and the image copy would otherwise win. Build with
`BABASHKA_CREMA=true script/compile`.

## Measurements

Built from master `401983b7` with `BABASHKA_LIBFFI=none` and cream's
`-H:Preserve` package list.

| GraalVM | bb | bb + Crema | build secs |
|---|---|---|---|
| 25.1.3 | 73.8 MB | 203.6 MB | 40 / 64 |
| 25.3.4.1-dev | 72.3 MB | 181.4 MB | 39 / 63 |

The extra size is interpreter metadata for every type and method in the
image. No native-image option restricts it.

Startup stays at 12 to 23 ms for all four binaries.

commons-codec, commons-lang3, jsoup, commonmark, gson and joda-time work.
Caffeine works only with a `^Cache` type hint, otherwise SCI fails with
"Can't call public method of non-public class".

A package missing from `-H:Preserve` aborts the process instead of
throwing. Examples are `Enum.<init>` without `java.lang` and
`LambdaMetafactory` without `java.lang.invoke`. gson fails on
`AtomicIntegerArray` with only `java.lang`, `java.lang.invoke`,
`java.util` and `java.util.function` preserved.

100k calls to `DigestUtils/sha256Hex`:

| runtime | ms |
|---|---|
| bb + Crema, 25.1.3 | 670 to 1800 |
| bb + Crema, 25.3.4.1-dev | 260 to 600 |
| cream, JIT on | 124 |
| JVM | 42 |

GraalVM 25.0.4 builds with Crema at 144.6 MB, but reflection on
run-time loaded classes is unfinished there ("enclosing class is not
supported yet"), so it cannot run these libraries.

## Decision

The default bb build stays without Crema. It would make the binary 2.5
times larger, needs a GraalVM early-access build, and aborts on a
missing preserve.

Revisit when a GraalVM release reduces the Crema metadata or allows
restricting it to selected packages.
