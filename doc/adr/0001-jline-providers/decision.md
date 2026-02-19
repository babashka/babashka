# ADR 0001: JLine Terminal Provider Strategy

## Status

Accepted

## Context

Babashka includes JLine for terminal/TUI support. JLine supports multiple terminal providers:

- **FFM (Foreign Function Memory)**: Direct native calls via Java's FFM API
- **Exec**: Shells out to commands like `stty`, `tty`, `test -t`
- **JNI**: Native library (not used in babashka)

We needed to decide which providers to include and how to handle platform differences.

### Key Findings from Benchmarking

| Operation | Exec (ms) | FFM (ms) | Speedup |
|-----------|-----------|----------|---------|
| TTY detection | 1.33 | 0.01 | 94x |
| getSize | 1.17 | 0.006 | 207x |
| getAttributes | 1.64 | 0.005 | 304x |
| enterRawMode | 7.26 | 0.03 | 240x |
| write+flush | 0.02 | 0.02 | 1.0x |
| Display.update (full) | 0.54 | 0.54 | 1.0x |
| Display.update (diff) | 0.03 | 0.03 | 1.0x |

Key insight: **Display.update performance is identical** because JLine's Display class caches terminal size/attributes and doesn't shell out during rendering. The expensive exec operations only happen at startup and on resize.

You can verify these results yourself by running `bb benchmark.clj` (included in this directory).

### Platform Constraints

- **Linux musl (static builds)**: FFM fails because it tries to `dlopen("libc.so.6")` which doesn't exist on musl. Only exec works.
- **Windows native (cmd.exe, PowerShell)**: Exec requires `stty`/`tty` commands which don't exist. FFM calls Windows Console APIs directly.
- **Windows Git Bash/MSYS/Cygwin**: Exec works (has `stty.exe` etc.)
- **macOS, Linux glibc**: Both FFM and exec work.

## Decision

Include both FFM and Exec providers with the following strategy:

1. **FFM as primary provider** on platforms where it works:
   - macOS
   - Linux with glibc
   - Windows (native console support)

2. **Exec as fallback** on platforms where FFM doesn't work:
   - Linux musl static builds

3. **TTY detection** via `babashka.terminal/tty?`:
   - Tries FFM first, falls back to exec (same strategy as terminal creation)
   - Returns true if fd is available for terminal use
   - Returns false if not a TTY OR if stream is already in use by active terminal
   - This semantic is intentional: it answers "can I create a terminal on this?" not just "is this a TTY device?"

## Consequences

### Positive

- Cross-platform TUI support everywhere
- Native Windows console support (no Git Bash requirement)
- Optimal performance on macOS/Linux glibc/Windows via FFM
- Static Linux builds still work via exec fallback
- Practical TUI performance is identical due to Display caching

### Negative

- Slightly larger binary (includes both providers)
- Startup ~10ms slower on musl static builds (exec raw mode setup)
- Resize handling ~2ms slower on musl (exec size query)

### Neutral

- JLine is well-architected: Display layer insulates apps from provider differences
- Exec fallback is solid - `stty` is POSIX standard, available everywhere on Unix

## Notes

- `stty` and `test` are part of POSIX and coreutils, available on all Unix systems including Alpine/musl
- The ~10ms startup difference is imperceptible to users
- For 60fps TUI apps, both providers leave 95%+ of frame budget for app logic

## Rebel-readline compatibility

Goal: run rebel-readline from source on babashka.

### Architecture changes in rebel-readline

Proxying `LineReaderImpl` with `IDeref`/`IAtom` (as rebel-readline originally did) adds ~200KB to the native binary. Instead:

- Replaced `(proxy [LineReaderImpl IDeref IAtom] ...)` with a normal `LineReaderBuilder`-created reader
- Store service state in a `*state*` atom instead of making the reader derefable/swappable
- Replaced the `selfInsert` proxy-super pattern with a widget wrapper:
  ```clojure
  (let [widgets (.getWidgets reader)
        orig    (.get widgets LineReader/SELF_INSERT)]
    (.put widgets "self-insert"
      (reify Widget
        (apply [_]
          (run-hooks)
          (.apply ^Widget orig)))))
  ```
- Changed `proxy [java.util.function.Supplier]` to `reify` (Supplier already in bb's reify interfaces)
- Changed `catch NoSuchFieldException` to `catch Exception` in `get-accessible-field` for bb compatibility
- Added `^Widget` type hint on `.apply orig` so SCI resolves through the interface (anonymous inner classes aren't directly accessible)
- Bumped compliment from 0.6.0 to 0.7.1 (0.6.0 had `^Package` type hint that bb couldn't resolve)

### Classes added to babashka

- `java.util.stream.Collectors` (for compliment)
- `java.lang.reflect.Constructor` with getName/getModifiers/getParameterTypes/getParameterCount (for compliment)
- `java.lang.reflect.Executable` with getParameterCount (for compliment)
- `java.lang.StackOverflowError` (instance check + import)
- `org.jline.reader.Buffer` (interface, with public-class upcast from BufferImpl)
- `java.util.Comparator` added to reify interfaces (impl-java)

### LineReaderImpl reflection config

`LineReaderImpl` is removed from the bare `:all` list (which gave full reflection) and moved to `:custom` with explicit methods and fields:

- Fields: `post` (for eldoc display), `size` (for terminal size)
- Impl-only methods: `redisplay`, `readBinding`, `defaultKeyMaps`, `setCompleter`, `setHighlighter`, `setParser`
- Inherited methods via `:inherit [org.jline.reader.LineReader]` — pulls in all public declared methods from the LineReader interface

The `:inherit` mechanism in `classes.clj` solves a GraalVM reflection problem: when a class has explicit `:methods` (not `:all`), type-hinting as `^LineReaderImpl` can't find inherited interface methods like `setOpt` via reflection. The `:inherit` key declares which superclass/interface methods to include in the reflection config, so inherited methods resolve correctly without needing `:all`.

### No LineReaderImpl references in rebel-readline

rebel-readline no longer imports or type-hints `LineReaderImpl`. All method calls resolve via reflection at runtime, which works because babashka's `:inherit` mechanism includes the full LineReader interface methods in the LineReaderImpl reflection config. The nested import syntax `impl.LineReaderImpl` under `org.jline.reader` is not supported by SCI — use `[org.jline.reader.impl LineReaderImpl]` if ever needed.

### Proxy cases added

- `org.jline.reader.Completer`
- `org.jline.reader.Highlighter`
- `org.jline.reader.ParsedLine` (with `clojure.lang.IMeta`)
- `java.io.Writer`
- `java.io.Reader`

### Vars added to babashka

In `clojure.repl` (as bb overrides, not in SCI):
- `special-doc` (private) — inlined special-doc-map for special form documentation
- `set-break-handler!` — signal handler registration; zero-arg is no-op since `Thread.stop` doesn't work in modern JVMs

In `clojure.main`:
- `repl-read`

### Babashka REPL integration

- `start-repl!` skips `repl-with-jline` when custom `:read` is provided in opts, so rebel-readline's read hook isn't clobbered by bb's own JLine REPL
- bb.edn for rebel-readline excludes jline-terminal, jline-terminal-jni, jline-terminal-ffm (keeps jline-reader) to avoid conflicts with bb's built-in JLine
- rebel-readline's deps.edn needs `org.jline/jline-terminal-ffm` for JVM development

### Public-class upcasts for JLine anonymous inner classes

JLine uses anonymous inner classes internally (e.g. `LineReaderImpl$2` for Widget, `LineReaderImpl$3` for ParsedLine). SCI can't resolve methods on these directly. Fixed with public-class upcasts in `classes.clj`:
- `org.jline.reader.Buffer` (from `BufferImpl`)
- `org.jline.reader.ParsedLine` (from `LineReaderImpl$3`)

### TODO

- Completion triggers `NoSuchFieldException: ns` — compliment's `populate-global-members-cache` calls `.getField dc name` without a try/catch. When a class has a field whose declaring class doesn't expose it publicly, it throws. Fix in compliment: wrap `.getField` in try/catch like rebel-readline's `get-accessible-field`.

