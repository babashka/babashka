# ADR 0009: Dynamic Binary as Default on Linux amd64

## Status

Accepted. Supersedes [ADR 0003](../0003-linux-static-default/decision.md).

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
- The static default dates from 2022 (`683752c6`), when glibc mismatches of
  the dynamic build broke installs on older distros.
- The dynamic amd64 binary builds on a Debian bullseye image. Its glibc floor
  is 2.31, enforced by `script/check_glibc.sh`. Debian 11, Ubuntu 20.04 and
  later, and RHEL 9 meet the floor. RHEL 8 and Amazon Linux 2 do not.

## Decision

1. The install script defaults to the dynamic binary on Linux amd64.
2. The install script falls back to the static binary on musl systems,
   detected via `/lib/ld-musl-*`, and on glibc older than 2.31. `--static`
   and `--dynamic` still override. FreeBSD keeps the static binary.
3. The glibc floor stays at 2.31 via the Debian bullseye build image and
   `script/check_glibc.sh`. The floor in the install script must match.
4. The dynamic amd64 build switches to `-H:+StaticExecutableWithDynamicLibC`
   via `BABASHKA_MOSTLY_STATIC=true`, matching aarch64, so only glibc remains
   a runtime dependency.
5. Builds that link zlib statically use a pinned zlib built by
   `script/setup-zlib` instead of the distro `libz.a`. The version matches
   the zlib bundled in the GraalVM musl toolchain used by setup-graalvm,
   currently 1.2.13, the same version `script/setup-musl` pins.

## Consequences

### Positive

- FFI works on the default Linux amd64 install.
- glibc users get the faster allocator.
- amd64 and aarch64 defaults behave the same.
- The zlib linked into the binary is pinned and checksummed instead of
  whatever the build image ships.

### Negative

- The install script grows detection logic.
- Alpine and old-glibc users depend on that detection and still get a binary
  without FFI.

### Neutral

- Both binaries remain published. Release artifacts keep their names, the
  mostly static amd64 binary replaces the dynamic one under the existing
  `babashka-<version>-linux-amd64.tar.gz` name.

## Notes

- zlib 1.3.2 is the latest release. Moving past 1.2.13 means diverging from
  the GraalVM musl toolchain. Revisit when the toolchain updates.
- setup-babashka and other installers may bypass the install script. Check
  before announcing the new default.
