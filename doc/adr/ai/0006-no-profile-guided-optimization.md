# ADR 0006: build bb without PGO

## Status

Recorded 2026-10-31. Rejected for now. The CI wiring, training script and
refresh script are on the unmerged branch `pgo-builds`.

## Context

Oracle GraalVM builds an instrumented binary with `--pgo-instrument`. A run of
that binary writes `default.iprof`, and `--pgo=default.iprof` builds the final
binary from it. bb's CI used PGO from #1629 until `d854de88` (2023-10-11)
commented it out, with no reason recorded. `-O1` came in with `6c1a66e8`
(2023-09-27) and stays in `native-image.properties` to keep builds short.

## Build time

Local build, macOS aarch64, GraalVM 25.1.3, master `712da426`.

| build | secs |
|---|---|
| `-O1` | 39 |
| `-O2` | 55 |
| `-O3` | 73 |
| `--pgo` | 41 |
| `--pgo-instrument` | 70 |

`--pgo` forces `-O3`, also when `-O1` is set. With a profile it takes about as
long as `-O1`.

Training per build adds an instrumented build and a training run to every
platform, about 3x on the native step. That rules it out. The only candidate
is a stored profile, trained once and downloaded by both CI systems.

A profile from macOS aarch64 built and ran on all six platforms, including
macOS amd64 on GraalVM 25.0.1. `--pgo` builds took between 17 s less and 93 s
more than `-O1` in the same job.

`native-image-utils merge-pgo-profiles` is missing from GraalVM 25.0.4, so
training is one run. The profile is 15 MB, 2.5 MB compressed. At bb's release
rate it belongs in `babashka/pgo-profiles`, not in git.

## What it gains

Against `-O1` in the same CI job, with a profile from the same commit,
`script/pgo/train.clj` and the flag below.

| platform | malli validate | load | size MB, `-O1` | size MB, PGO |
|---|---|---|---|---|
| Linux amd64 | -34% | -19% | 90.9 | 95.0 |
| Linux static | -25% | -22% | 72.7 | 76.5 |
| Linux aarch64 static | -33% | -24% | 93.2 | 98.1 |
| macOS amd64 | -38% | -21% | 72.6 | 76.7 |
| macOS aarch64 | -14% | -23% | 75.4 | 80.4 |
| Windows | -32% | -26% | 77.1 | 80.4 |

Load is `require` of malli, honeysql, loom, medley and camel-snake-kebab from
source. Libraries outside training gained as much, -34% locally. A 10M
`loop` with `inc` and `dec` gained 24%.

## Why it was rejected

The binary depends on inputs that cannot be read or reviewed, and the
consequences appear only after measuring.

Size follows the training script.

| training | Linux amd64 MB | Windows MB |
|---|---|---|
| none, `-O1` | 90.9 | 77.1 |
| short | 86.2 | 71.5 |
| short, flag | 88.9 | 74.4 |
| extended, flag | 95.0 | 80.4 |

Size also follows the GraalVM version. Extended training took 2 MB on 25.1.3
and 6 MB on 25.0.4. The cause was not found.

Code outside training gets slower. With the short training, against `-O1`:

| workload | change |
|---|---|
| transit | +33% |
| `Instant/parse` | +25% |
| `fs/glob` | +9% |
| yaml | +6% |
| HTTP client | +6% |
| SHA-256 | +6% |

Adding them to training gave -50% on transit and -44% on `Instant/parse`, at
the size cost above. Any built-in not in training stays exposed, and nothing
reports it.

`-O3` changes behavior. It removes the message of implicit exceptions:

```
-O1: Message:  clojure.lang.Keyword cannot be cast to clojure.lang.Associative
PGO: Message:  Stack trace is imprecise, the top frames are missing and/or have wrong line numbers. To get precise stack traces, build the image with option -H:-ReduceImplicitExceptionStackTraceInformation
```

`-H:-ReduceImplicitExceptionStackTraceInformation` restores the message for
about 3 MB. `error_test.clj` found this. Other changes of this kind may have no
test.

A profile goes stale and nothing reports it. A profile from `v1.13.229`, four
days old, used on master:

| workload | fresh | stale |
|---|---|---|
| malli validate | -20% | -1% |
| csk | -19% | -9% |
| load | -31% | -29% |

Only 248 of 662 generated sci class names still matched. Clojure numbers `fn`
and `reify` classes with a global counter, and the profile matches types by
name. The commits in between also changed the same closure code, so the share
of each cause is unknown.

Each release needs a manual training step with CI's GraalVM, and a login that
can create releases on `babashka/pgo-profiles`.

A change in sci reads as a diff and keeps its gain. A profile does neither.

## Decision

bb stays on `-O1` without a profile. Speed work goes into sci.

Revisit when GraalVM reports profile mismatches, or when size and behavior no
longer depend on what the training covers.
