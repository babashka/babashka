# ADR 0008: Source for built-in vars

## Status

Accepted

## Context

`(source inc)` and other built-in vars return "Source not found" because SCI's
`copy-var` / `copy-core-var` did not preserve `:file` and `:line` metadata from
the original Clojure vars. Issue: https://github.com/babashka/babashka/issues/1935

## Approach

### 1. Preserve `:file`, `:line` and `:column` in SCI

- Internal `var-meta` in `sci.impl.copy-vars` now includes `:file`, `:line` and
  `:column` from the original var metadata (gated behind `elide-vars`).
- Public `sci.core/copy-var` and `copy-var*` also preserve these fields.
- `sci.core/copy-ns` default metadata now includes `:file`, `:line` and
  `:column` (alongside `:arglists`, `:doc`, `:macro`, etc.).

### 2. Split `clojure-core` map to avoid "Method code too large"

Adding `:file`/`:line`/`:column` to every var increases the bytecode size of the
`clojure-core` namespace map in `sci.impl.namespaces`. This was already at the
JVM 64KB method limit.

Split into two maps merged together, each wrapped in `avoid-method-too-large`:

```clojure
(def clojure-core
  (merge
   (avoid-method-too-large
    {:obj clojure-core-ns
     ;; ... first half (A-J) ...
     })
   (avoid-method-too-large
    {;; ... second half (K-Z) ...
     })))
```

### 3. Deduplicate built-in entries in stacktraces

Built-in vars now appear twice in stacktraces: once from the SCI call-site
(`:sci/built-in true`, no file info) and once from the var metadata (with
`:file`/`:line`). `format-stacktrace` deduplicates by replacing a `<built-in>`
entry with the following file-location entry when they refer to the same var.

### 4. Look up source from Maven jars via isolated classloader

`source-fn` in `babashka.impl.clojure.repl` finds source files through:

1. Local file on disk (user code with file paths)
2. bb's runtime URLClassLoader (user-added classpath via `-cp` or `bb.edn`)
3. An isolated URLClassLoader (created lazily via `delay`) that resolves
   bb's built-in library jars from `~/.m2/repository` using
   `print-deps/deps-classpath`

The isolated classloader uses `nil` parent to avoid polluting bb's runtime
classpath — source jars are only used for reading text, not loading code. This
prevents issues like `(require 'clojure.core :reload)` triggering reflection
errors in native image.

## Binary size

Baseline (master, macOS arm64): 71,098,992 bytes (68M)

With changes (split + :file/:line/:column metadata): 71,098,992 bytes (68M) — zero increase

Verified by building both with and without the metadata changes: identical size.
String literals are interned (most vars share "clojure/core.clj") and per-var
line numbers (ints) have negligible impact.
