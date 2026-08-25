# ADR 0010: Raise the glibc Floor to 2.28

## Status

Accepted. Amends [ADR 0009](../0009-linux-dynamic-default/decision.md),
which set the floor at 2.17.

## Context

ADR 0009 set the glibc floor of the dynamic Linux binaries at 2.17, the
manylinux2014 baseline, which covers everything from the RHEL 7 era up.

Linking libffi into the binary raises the floor. libffi's closure
allocator calls `memfd_create`, which glibc added in 2.27, so
`script/verify_link` fails the build with "glibc symbol version 2.27
exceeds floor 2.17". The guard did its job: without it the Linux binary
would have stopped running on older distributions without anyone
noticing. Closures are the upcall side of libffi, which babashka does
not call today, but it will if libffi ever backs callbacks as well.

The ecosystem has moved on from 2.17. CentOS 7, the platform
manylinux2014 was built on, reached end of life on 30 June 2024. The
current common baseline is manylinux_2_28, built on AlmaLinux 8, and
projects have followed: PyPy moved its Linux builds to glibc 2.28 in
July 2026.

Every supported LTS distribution is at or above 2.28:

| distribution | glibc |
|---|---|
| RHEL 8, AlmaLinux 8, Debian 10 | 2.28 |
| Ubuntu 20.04, Debian 11 | 2.31 |
| RHEL 9, Amazon Linux 2023 | 2.34 |
| Ubuntu 22.04 | 2.35 |
| Debian 12 | 2.36 |
| Ubuntu 24.04 | 2.39 |

## Decision

The glibc floor is 2.28, defined once in `script/glibc_floor.sh` and
mirrored in the install script's `min_glibc_version`, as before.

Systems below the floor lose nothing they have today: the install script
already falls back to the static binary when glibc is older than the
floor, so RHEL 7 and CentOS 7 keep getting a binary that runs. They get
the musl static build, which has no FFI at all, `dlopen` being absent
there.

The pre-build probe in `script/check_glibc.sh` gains meaning rather than
losing it. It links `fcntl`, whose version bumped at exactly 2.28, so the
probe now measures the boundary itself.

## Consequences

- libffi links without a special build. Struct support and, later,
  libffi-backed callbacks need no closure surgery.
- babashka's dynamic Linux binaries require glibc 2.28 or newer. The
  distributions this drops, RHEL 7 and CentOS 7, have been out of support
  for over a year.
- The floor now matches what the wider ecosystem ships against, so a
  future bump is a question of following manylinux rather than of
  babashka's own choices.
