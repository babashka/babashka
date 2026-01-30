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

