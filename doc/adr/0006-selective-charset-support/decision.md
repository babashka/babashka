# ADR 0006: Selective Charset Support via GraalVM Feature

## Status

Accepted

## Context

A user reported that `(slurp "file" :encoding "cp437")` throws
`java.io.UnsupportedEncodingException` in the native binary. cp437 (IBM437) is
a common encoding used in DOS/Windows console applications and legacy data
files.

GraalVM native-image only includes 7 charsets by default:

- US-ASCII
- ISO-8859-1
- UTF-8
- UTF-16
- UTF-16BE
- UTF-16LE
- The system default (typically UTF-8)

All other charsets require explicit inclusion.

GraalVM replaces `Charset.forName()` at runtime with a substitution that looks
up charsets from a `HashMap` baked into the image heap at build time
(`LocalizationSupport.charsets`). The JVM's `ServiceLoader`-based charset
discovery is completely bypassed at runtime, so removing the `CharsetProvider`
ServiceLoader exclusion alone does not help.

## Options Considered

### 1. `--add-all-charsets` / `-H:+AddAllCharsets`

The built-in GraalVM flag that includes all 173 available charsets.

- **Pro**: Simple one-line change
- **Con**: Adds ~5MB to the binary, mostly from CJK encoding tables
  (`sun.nio.cs.ext.*` — Shift_JIS, EUC-JP, GBK, Big5, etc.)

### 2. Remove the `CharsetProvider` ServiceLoader exclusion

Babashka excludes `java.nio.charset.spi.CharsetProvider` from ServiceLoader
discovery. Removing this exclusion was considered.

- **Result**: Does not work. GraalVM's runtime substitution of
  `Charset.forName()` only checks the `LocalizationSupport.charsets` map,
  never the ServiceLoader. The exclusion is irrelevant to runtime behavior.

### 3. Custom GraalVM Feature with selective charset registration

Write a GraalVM `Feature` class that calls `LocalizationFeature.addCharset()`
for specific charsets during `beforeAnalysis`.

- **Pro**: Only adds the charsets we need, minimal binary size impact
- **Con**: Requires access to GraalVM internal API (`LocalizationFeature` is
  in an unexported module), needs `--add-exports` JVM flag

## Decision

Option 3 — a custom GraalVM Feature that selectively registers charsets.

### Implementation

A new library `impl-graal-features` (modeled after `impl-java` and
`clj-easy/graal-build-time`) contains `babashka.impl.CharsetsFeature`, a
GraalVM `Feature` that calls `LocalizationFeature.addCharset()` for each
charset we want to add.

The library is pre-compiled against `org.graalvm.nativeimage/svm` from Maven
(which contains `LocalizationFeature`) and installed as a jar dependency.
At native-image build time, the Feature runs during `beforeAnalysis` and
registers the extra charsets into the `LocalizationSupport.charsets` map.

Because `LocalizationFeature` is in the `org.graalvm.nativeimage.builder`
module which does not export its localization package, we add a `JavaArgs`
entry in `native-image.properties`:

```
JavaArgs=--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.hosted.jdk.localization=ALL-UNNAMED
```

### Charsets Added

Currently only IBM437 (cp437), the charset that triggered this work. The array
in `CharsetsFeature.java` can be extended as needed.

### Key Source Files

- `impl-graal-features/src-java/babashka/impl/CharsetsFeature.java` — the Feature
- `impl-graal-features/build.clj` — builds the jar against the SVM dependency
- `impl-graal-features/deps.edn` — declares `:svm` alias with `org.graalvm.nativeimage/svm`
- `native-image.properties` — `--features=babashka.impl.CharsetsFeature` and `JavaArgs`

## Consequences

### Positive

- cp437 (and any future charsets) work in native binary
- Negligible binary size impact compared to `AddAllCharsets` (~5MB savings)
- Easy to extend — adding a charset is one string in the array
- Follows established patterns (`impl-java`, `graal-build-time`)

### Negative

- Depends on GraalVM internal API (`LocalizationFeature.addCharset`), which
  could change between GraalVM versions
- Requires `--add-exports` JVM flag for module access
- New charsets require rebuilding the `impl-graal-features` jar
