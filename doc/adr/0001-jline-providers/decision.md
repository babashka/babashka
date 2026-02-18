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

### Done

Classes added to babashka:
- `org.jline.reader.Highlighter` (interface, reify support added)
- `org.jline.reader.LineReader$Option` (enum)
- `org.jline.terminal.Attributes$InputFlag` (enum)
- `org.jline.terminal.Attributes$LocalFlag` (enum)

### Remaining blockers

1. `proxy-super` in SCI: SCI's `proxy` macro doesn't support `proxy-super` in user code. Rebel-readline needs `(proxy-super parse line cursor context)` to fall back to `DefaultParser`'s parse method. This requires SCI changes.

2. `org.jline.reader.impl.DefaultParser`: rebel-readline uses `(proxy [DefaultParser] ...)` to extend the default parser, overriding `isDelimiterChar` and `parse`. DefaultParser is stable (since 2002) and babashka already exposes `impl.LineReaderImpl`, so exposing it is consistent. Needs a proxy case in `babashka.impl.proxy`.

3. `org.jline.reader.impl.BufferImpl`: only used in a dev helper (`buffer*`), not needed at runtime. Can be avoided on the rebel-readline side.

4. `org.jline.terminal.impl.DumbTerminal`: only used for an `instance?` check to detect bad terminals. Can be avoided on the rebel-readline side (e.g. check `.getType` instead).

### Alternatives to avoid on rebel-readline side

Proxying `LineReaderImpl` with `IDeref`/`IAtom` (as rebel-readline currently does) adds ~200KB to the native binary. Instead:

- Replace `(proxy [LineReaderImpl IDeref IAtom] ...)` with a normal `LineReaderBuilder`-created reader
- Store service state in a separate atom bound to a dynamic var instead of making the reader derefable/swappable
- Replace the `selfInsert` proxy-super pattern with a widget wrapper:
  ```clojure
  (let [widgets (.getWidgets reader)
        orig    (.get widgets LineReader/SELF_INSERT)]
    (.put widgets "self-insert"
      (reify Widget
        (apply [_]
          (run-hooks)
          (.apply orig)))))
  ```
  This avoids proxying `LineReaderImpl` entirely.

