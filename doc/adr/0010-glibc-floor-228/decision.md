# ADR 0010: Raise the glibc Floor to 2.28

## Status

Accepted. Amends [ADR 0009](../0009-linux-dynamic-default/decision.md),
which set the floor at 2.17.

## Context

ADR 0009 set glibc 2.17 as the minimum version for dynamic Linux binaries.
This version is the manylinux2014 baseline.

Linking libffi increases this minimum version. Its closure allocator calls
`memfd_create`, which glibc added in 2.27. Thus, `script/verify_link` rejects
the old limit. Babashka does not currently use libffi closures. It can use
them for callbacks in the future.

CentOS 7 reached end of life on June 30, 2024. Many projects now use the
manylinux_2_28 baseline. PyPy moved its Linux builds to glibc 2.28 in July
2026.

These distributions provide glibc 2.28 or newer:

| distribution | glibc |
|---|---|
| RHEL 8, AlmaLinux 8, Debian 10 | 2.28 |
| Ubuntu 20.04, Debian 11 | 2.31 |
| RHEL 9, Amazon Linux 2023 | 2.34 |
| Ubuntu 22.04 | 2.35 |
| Debian 12 | 2.36 |
| Ubuntu 24.04 | 2.39 |

## Decision

The minimum glibc version is 2.28. `script/glibc_floor.sh` defines this value.
The install script copies it in `min_glibc_version`.

On older systems, the install script selects the static binary. This binary
uses musl and does not provide `dlopen`. Thus, it cannot load shared libraries
through FFI.

The probe in `script/check_glibc.sh` links `fcntl`, which requires glibc 2.28.
Thus, the probe measures the new limit.

## Consequences

- libffi links without a custom build. Struct support and future callbacks can
  use the complete library.
- Dynamic Linux binaries require glibc 2.28 or newer.
- The minimum version matches the manylinux_2_28 baseline.
