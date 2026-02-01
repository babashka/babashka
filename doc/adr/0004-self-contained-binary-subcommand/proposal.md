# `bb binary` - Self-Contained Executable Builder

## Problem Statement

Creating self-contained babashka executables currently requires:
1. Manually downloading bb binaries for each target OS/arch
2. Creating an uberjar
3. Concatenating bb + uberjar with `cat`
4. Making executable and handling platform-specific details

This is tedious, especially when targeting multiple platforms.

## Proposed Solution

A `bb binary` subcommand that automates this process.

## Command Interface

```bash
# From uberjar
bb binary --uberjar foo.jar --out dist/

# From source (builds uberjar internally)
bb binary --main foo.bar --classpath src:resources --out dist/

# Specify targets (default: current platform only)
bb binary --uberjar foo.jar --target linux-amd64,macos-amd64,macos-aarch64,windows-amd64

# Specify bb version (default: same as running bb)
bb binary --uberjar foo.jar --bb-version 1.4.0
bb binary --uberjar foo.jar --bb-version latest

# Custom output name (default: derived from jar name or --name)
bb binary --uberjar foo.jar --name myapp
```

## Supported Targets

| Target | Output |
|--------|--------|
| `linux-amd64` | `myapp-linux-amd64` |
| `linux-aarch64` | `myapp-linux-aarch64` |
| `macos-amd64` | `myapp-macos-amd64` |
| `macos-aarch64` | `myapp-macos-aarch64` |
| `windows-amd64` | `myapp-windows-amd64.exe` |

## Key Design Decisions

### 1. Subcommand vs Task Function?

Both could be useful:
- **Subcommand** (`bb binary`): Simple one-off builds, CI/CD friendly
- **Task function** (`bb.binary/build`): Integrates into `bb.edn` tasks, more programmatic control

Recommendation: Start with subcommand, consider adding task API later.

### 2. How to handle bb binary downloads?

- Download from GitHub releases to `~/.babashka/binary-cache/<version>/`
- Verify checksums against published SHA256
- Reuse cached binaries across builds

### 3. Uberjar creation when using `--main`/`--classpath`?

Use `babashka.deps/clojure` or shell out to `bb uberjar` internally.

### 4. What about static Linux binaries?

Could add `--linux-static` flag to use the musl-based static builds instead of dynamically linked ones.

## Open Questions

1. **Should `--target all` be supported?** Convenience vs. accidentally building for platforms you don't need.

2. **Compression?** The concatenated binary works but is large. Should we support UPX compression as an option?

3. **Code signing for macOS?** This is outside bb's scope, but we could document the workflow or provide a `--sign` flag that shells out to `codesign`.

4. **Windows considerations?** The `.exe` extension is handled, but are there other Windows-specific concerns?

5. **Should it create archives?** E.g., `--archive tar.gz` to produce `myapp-linux-amd64.tar.gz` ready for release.

## Example Workflow

```bash
# Build for all major platforms
bb binary \
  --uberjar target/myapp.jar \
  --name myapp \
  --target linux-amd64,macos-amd64,macos-aarch64,windows-amd64 \
  --out dist/

# Result:
# dist/myapp-linux-amd64
# dist/myapp-macos-amd64
# dist/myapp-macos-aarch64
# dist/myapp-windows-amd64.exe
```

## Task API Alternative

```clojure
;; bb.edn
{:tasks
 {build-binaries
  {:requires ([babashka.binary :as binary])
   :task (binary/build
          {:uberjar "target/myapp.jar"
           :name "myapp"
           :targets [:linux-amd64 :macos-aarch64]
           :out "dist/"})}}}
```
