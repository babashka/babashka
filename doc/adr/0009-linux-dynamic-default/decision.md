# ADR 0009: Dynamic Binary as Default on Linux amd64

## Status

Accepted, implementation pending. Supersedes [ADR 0003](../0003-linux-static-default/decision.md).

## Context

ADR 0003 kept the musl static binary as the default Linux amd64 install when
JLine was added. JLine has a working fallback provider on musl, so static cost
little at the time.

FFI changed that. `babashka.ffi` cannot work in a fully static musl binary
because `dlopen` cannot load shared libraries there. The default Linux amd64
install is the one binary where the feature does not exist. The install script
gained a `--dynamic` flag as a stopgap for dev builds.

Other factors:

- The aarch64 "static" build uses `-H:+StaticExecutableWithDynamicLibC` and
  links glibc dynamically. FFI works there. Alpine aarch64 users are already
  not served by the default.
- musl malloc is slower than glibc malloc under threads.
- The static default dates from 2022 (`683752c6`), when the glibc floor of the
  dynamic build broke installs on older distros.
- The dynamic amd64 binary currently builds on `cimg/clojure:1.11.1`
  (Ubuntu 22.04, glibc 2.35). That floor excludes RHEL 9, Debian 11 and
  Amazon Linux 2. Flipping the default without lowering the floor would trade
  "FFI does not work" for "bb does not start" on those systems.

## Decision

1. The install script defaults to the dynamic binary on Linux amd64.
2. The install script detects musl, for example via `/lib/ld-musl-*` or
   `ldd --version`, and falls back to the static binary. `--static` and
   `--dynamic` still override.
3. When the detected glibc is older than the floor of the dynamic build, the
   install script also falls back to the static binary.
4. The dynamic amd64 build moves to an older base image to lower its glibc
   floor to roughly 2.28-2.31.
5. The dynamic amd64 build switches to `-H:+StaticExecutableWithDynamicLibC`,
   matching aarch64, so only glibc remains a runtime dependency.

## Consequences

### Positive

- FFI works on the default Linux amd64 install.
- glibc users get the faster allocator.
- amd64 and aarch64 defaults behave the same.

### Negative

- The install script grows detection logic.
- Alpine and old-glibc users depend on that detection and still get a binary
  without FFI.

### Neutral

- Both binaries remain published. Release artifacts do not change.

## Implementation notes

- Verify the actual floor of the released dynamic binary with
  `objdump -T bb | grep GLIBC_` before picking the new base image.
- Check what setup-babashka and other installers do before flipping, they may
  bypass the install script.
