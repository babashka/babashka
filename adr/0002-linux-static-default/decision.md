# ADR 0002: Linux Static Binary as Default

## Status

Accepted

## Context

Babashka provides two Linux builds:

- **Static binary**: Compiled against musl, no external dependencies
- **Dynamic binary**: Compiled against glibc, requires compatible system glibc

The static binary has been the default for Linux installations (via install script, brew, etc.) for years. This ensures babashka works on virtually every x86_64 Linux system regardless of the glibc version installed.

When adding JLine for TUI support, we discovered that JLine's optimal provider (FFM - Foreign Function Memory) doesn't work on musl static builds because it tries to `dlopen("libc.so.6")` which doesn't exist. This raised the question: should we change the default to dynamic builds?

## Decision

**Keep static binary as the default for Linux.**

### Rationale

1. **JLine's fallback works well**: The exec provider (which shells out to `stty`, `test -t`, etc.) works on musl and performs almost equally well for practical TUI use cases. JLine's Display class caches terminal state, so the expensive shell-out operations only happen at startup and on resize - not during rendering. See [ADR 0001](../0001-jline-providers/decision.md) for benchmark details.

2. **Universal compatibility is valuable**: The static binary works on any Linux without worrying about glibc version mismatches. This is especially important for a scripting tool that users expect to "just work."

3. **No breaking change**: Users and tooling have relied on static being the default for years. Changing this would require coordination and could cause issues.

4. **Dynamic is still available**: Users who want FFM performance can install the dynamic build with a flag.

## Consequences

### Positive

- Babashka continues to work on all Linux systems
- No change required for existing users or installation tooling
- TUI features work via exec provider fallback
- Users can opt-in to dynamic build if needed

### Negative

- Static builds have slightly slower JLine startup (~10ms for raw mode setup)
- Resize handling is slower (~2ms per resize event)

### Neutral

- These performance differences are imperceptible in practice
- TUI rendering performance is identical (Display caches internally)

## Summary

Adding JLine doesn't change the Linux distribution strategy. Static binary remains the default with full TUI support via the exec provider. Users get more features (TUIs, improved REPL) without any downsides.
