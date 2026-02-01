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

## MVP Scope

The initial implementation focuses on creating executables from pre-built uberjars with cross-platform support.

### Command Interface

```bash
# Create executable from uberjar (required flag)
bb binary --uberjar foo.jar

# Specify output directory (default: current directory)
bb binary --uberjar foo.jar --out dist/

# Custom output name (default: derived from jar name)
bb binary --uberjar foo.jar --name myapp

# Specify target platform(s) (default: current platform)
bb binary --uberjar foo.jar --target linux-amd64,macos-aarch64

# Specify bb version (default: current bb version)
bb binary --uberjar foo.jar --bb-version 1.4.0
```

### Supported Targets

| Target | Output |
|--------|--------|
| `linux-amd64` | `myapp-linux-amd64` |
| `linux-aarch64` | `myapp-linux-aarch64` |
| `macos-amd64` | `myapp-macos-amd64` |
| `macos-aarch64` | `myapp-macos-aarch64` |
| `windows-amd64` | `myapp-windows-amd64.exe` |

### Binary Caching

- Download bb binaries from GitHub releases to `~/.babashka/binary-cache/<version>/`
- Verify checksums against published SHA256
- Reuse cached binaries across builds

### Example Workflow

```bash
# Build for multiple platforms
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

## Open Questions

1. **Should `--target all` be supported?** Convenience vs. accidentally building for platforms you don't need.

2. **Windows considerations?** The `.exe` extension is handled, but are there other Windows-specific concerns?

## Future Considerations

The following features are intentionally deferred from the MVP:

### Build from Source (`--main` + `--classpath`)

```bash
# Build uberjar internally from source
bb binary --main foo.bar --classpath src:resources --out dist/
```

Would use `babashka.deps/clojure` or shell out to `bb uberjar` internally.

### Task API (`babashka.binary/build`)

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

### Static Linux Binaries

`--linux-static` flag to use musl-based static builds instead of dynamically linked ones.

### Compression

UPX compression to reduce binary size.

### Code Signing

macOS code signing integration via `--sign` flag.

### Archive Creation

`--archive tar.gz` to produce release-ready archives like `myapp-linux-amd64.tar.gz`.
