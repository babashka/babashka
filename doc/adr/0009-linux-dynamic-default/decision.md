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
   detected via `/lib/ld-musl-*`, on glibc older than 2.31, and when
   `/lib64/ld-linux-x86-64.so.2 --verify` fails. The loader is executed
   rather than stat'ed because NixOS ships a stub at that path that only
   prints an error. `--static` and `--dynamic` still override. On FreeBSD
   the same probe runs against `/compat/linux`, with the glibc version
   asked from the linux loader itself, so a Linuxulator with a recent
   linux_base gets the dynamic binary and FFI.
3. The glibc floor stays at 2.31 via the Debian bullseye build image and
   `script/check_glibc.sh`. The floor in the install script must match.
4. Mostly static, `-H:+StaticExecutableWithDynamicLibC`, is the
   `script/compile` default on Linux, so only glibc remains a runtime
   dependency and local builds match the shipped artifacts.
   `BABASHKA_DYNAMIC=true` opts out. `BABASHKA_STATIC=true` with
   `BABASHKA_MUSL=true` builds the fully static binary. `BABASHKA_STATIC`
   alone behaves like the default.
5. Builds that link zlib statically use a pinned zlib built by
   `script/setup-zlib` instead of the distro `libz.a`. The version matches
   the zlib bundled in the GraalVM musl toolchain used by setup-graalvm,
   currently 1.2.13, the same version `script/setup-musl` pins.
6. `script/verify_link` runs after each Linux CI build and fails the job
   when the binary links an unexpected shared library, misses the pinned
   zlib or uses glibc symbols newer than the floor.

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

- The released 1.13.219 dynamic binary uses glibc symbols up to 2.17, well
  under the 2.31 floor. `script/verify_link` prints the value per build, so
  the install script floor can move down if it stays low.
- zlib 1.3.2 is the latest release. Moving past 1.2.13 means diverging from
  the GraalVM musl toolchain. Revisit when the toolchain updates.
- setup-babashka (turtlequeue) runs the install script fetched from master,
  so its users switch at merge time, before a release ships the mostly
  static artifact. Safe: the old dynamic artifacts run on glibc 2.17+ and
  system zlib is present wherever dpkg or rpm is.
- setup-clojure (DeLaGuardo) hardcodes the musl static artifact name on
  linux. Upstream PR prepared on fork borkdude/setup-clojure, branch
  bb-dynamic-linux.
- Nix users get bb via nixpkgs, which invokes native-image directly, so
  compile script defaults do not affect that packaging. For FFI on NixOS,
  declaring libraries via LD_LIBRARY_PATH in a devshell works, no bb
  changes needed.
