# ADR 0008: Source for built-in vars

## Status

In progress

## Context

`(source inc)` and other built-in vars return "Source not found" because SCI's
`copy-var` / `copy-core-var` did not preserve `:file` and `:line` metadata from
the original Clojure vars. Issue: https://github.com/babashka/babashka/issues/1935

## Approach

### 1. Preserve `:file` and `:line` in SCI's `copy-var`

The internal `var-meta` in `sci.impl.copy-vars` now includes `:file` and `:line`
from the original var metadata (gated behind `elide-vars` like `:doc` and
`:arglists`).

The public `sci.core/copy-var` and `copy-var*` also preserve these fields.

### 2. Split `clojure-core` map to avoid "Method code too large"

Adding `:file`/`:line` to every var increases the bytecode size of the
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

### 3. Look up source from Maven jars (TODO)

`source-fn` needs to find the source file. For built-in vars, `:file` is a
classpath-relative path like `"clojure/core.clj"`. Options:

- On JVM: load as classpath resource (jars are available)
- In native image: use `bb print-deps` to resolve library coordinates from
  `META-INF/babashka/deps.edn`, find jars in `~/.m2/repository`, read source
  from the jar

## Binary size

Baseline (master, macOS arm64): 71,098,992 bytes (68M)

With changes (split + :file/:line metadata): 71,098,992 bytes (68M) — zero increase

Verified by building both with and without the :file/:line change: identical size.
String literals are interned (most vars share "clojure/core.clj") and per-var
line numbers (ints) have negligible impact.
