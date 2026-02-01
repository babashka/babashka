# `bb-binary` - Self-Contained Executable Builder

## Problem Statement

Creating self-contained babashka executables currently requires:
1. Manually downloading bb binaries for each target OS/arch
2. Creating an uberjar
3. Concatenating bb + uberjar with `cat`
4. Making executable and handling platform-specific details

This is tedious, especially when targeting multiple platforms.

## Proposed Solution

A standalone tool (installable via bbin) and library that automates this process.

## Distribution

- **CLI**: Install via `bbin install io.github.babashka/bb-binary`
- **Library**: Add as dependency for use in bb.edn tasks

## MVP Scope

The CLI creates one executable at a time. For multi-target builds, use the library API.

### CLI Interface

```bash
# Create executable for current platform
bb-binary --uberjar foo.jar --out-file myapp

# Specify target platform
bb-binary --uberjar foo.jar --target linux-amd64 --out-file dist/myapp-linux-amd64

# Specify bb version (default: current bb version)
bb-binary --uberjar foo.jar --out-file myapp --bb-version 1.4.0
```

### Supported Targets

- `linux-amd64`
- `linux-aarch64`
- `macos-amd64`
- `macos-aarch64`
- `windows-amd64`

Linux targets use static (musl) binaries.

### Library API

```clojure
;; bb.edn
{:deps {io.github.babashka/bb-binary {:git/tag "v0.1.0" :git/sha "..."}}
 :tasks
 {release
  {:requires ([bb-binary.core :as binary])
   :task (doseq [target ["linux-amd64" "macos-aarch64" "windows-amd64"]]
           (binary/build {:uberjar "target/app.jar"
                          :target target
                          :out-file (str "dist/myapp-" target)}))}}}
```

### Binary Caching

- Download bb binaries from GitHub releases
- Cache location: `$XDG_CACHE_HOME/babashka/bb-binary/<version>/` (defaults to `~/.cache/babashka/bb-binary/<version>/`)
- Verify checksums against published SHA256
- Reuse cached binaries across builds

## Future Considerations

The following features are intentionally deferred from the MVP:

### Build from Source (`--main` + `--classpath`)

```bash
bb-binary --main foo.bar --classpath src:resources --out-file myapp
```

### Compression

UPX compression to reduce binary size.

### Code Signing

macOS code signing integration.

### Archive Creation

Produce release-ready archives like `myapp-linux-amd64.tar.gz`.
