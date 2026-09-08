# ADR 0002: babashka.mvn next to Grenadine

## Status

Recorded 2026-09-08 on branch `mvn-clj`. Compared against Grenadine 0.1.11
as checked out in `~/dev/grenadine`.

## Context

Grenadine ("pomegranate, with the JVM pressed out") is a dependency resolver
in portable Clojure by the ClojureStar project, for dialects without a Java
class library: Glojure and Jolt. babashka.mvn is the Maven procurer bb runs
under tools.deps so that `:mvn/version` coordinates resolve without a JVM.
Both exist because the Maven Java stack cannot come along, and both are
checked against tools.deps. The question this records is why bb did not
adopt Grenadine, and what to say to its authors.

## The two, side by side

| | Grenadine | babashka.mvn |
|---|---|---|
| Source | 5,326 lines, 17 namespaces, plus a 1,227-line CLI | 1,643 lines, 12 namespaces |
| Tests | 2,079 lines; an oracle against tools.deps 0.31.1642 | 499 assertions in scripts; an oracle against tools.deps 0.31.1638, warm and cold, 22 entries |
| tools.deps core | ported: `deps.clj` from `excluded?` to `expand-deps` becomes `expander.cljc` and `basis.cljc`, gitlibs becomes `gitlibs.cljc`, by unified patches over pinned revisions with SHA-256 ledgers | run as is, interpreted by sci: 36 vendored files verbatim, four stand-ins, one patch |
| Maven layer | its own: coordinates, POM model, metadata, sha1 sidecars, LATEST | its own: the same, plus settings.xml with mirrors, servers, encrypted passwords, proxies with credentials |
| settings.xml | none | full, because `bb clojure` must answer as `clojure` does on the same machine |
| Version ordering | adapted from `ComparableVersion`, Apache Maven 3.9.16 | ported from `GenericVersion`, maven-resolver-util 1.9.27 |
| Beyond tools.deps | lock files, three mediation strategies, `require-deps`, a CLI, a facade across dialects | nothing; `make-classpath2` is the only entry |
| Portability | `.cljc`, host effects injected, no JVM classes | bb only: babashka.fs, babashka.http-client, data.xml, javax.crypto |

## The shape

Same problem, opposite halves reimplemented.

Grenadine cannot run tools.deps: its targets have no Java class library. So it
ports the core, carefully, patch by patch against pinned upstream, and keeps a
thin transport underneath.

bb can run tools.deps: sci interprets Alex Miller's code and GraalVM gives it
the classes that code needs. The only thing bb cannot have is the Maven Java
stack, so that is the only thing replaced, behind the extension multimethods
tools.deps offers to procurers. See ADR 0011 for the measurements that led
there.

## Why not Grenadine in bb

1. bb already had tools.deps, interpreted, at 30 KB. That is zero semantic
   drift from upstream for free. A ported core, however well patched, is a
   second implementation of `expand-deps` to keep in step.
2. bb users have `~/.m2/settings.xml` with mirrors, credentials and proxies,
   and `bb clojure` has to give the same classpath as `clojure`. That forced
   the settings, cipher and proxy work; Grenadine's dialects have no such
   users yet.
3. bb's budget was image size, not portability. Every line was measured and
   checked against the JVM resolver; the oracle came before the code.
4. The audiences barely overlap: Glojure and Jolt on one side, a native binary
   with a JDK's worth of classes on the other. Two Maven layers for two
   runtimes is not duplication. One tools.deps core for two runtimes would
   have been worth sharing, and only Grenadine needs it.

## A bug worth passing on

tools.deps orders versions with Aether's `GenericVersion`, not Maven's
`ComparableVersion`, and the two disagree: `_` is a separator in one and not
the other, so `5.0_ALPHA` sorts below `5.0` on the JVM and above it under
`ComparableVersion`. babashka.mvn's first port made the same choice; the
oracle's `find-versions` check caught it and the port was redone from
maven-resolver-util. Grenadine's `version.cljc` says it adapts
`ComparableVersion`.

## What could be shared

The corpus and the harness. Both projects test against the same tools.deps.
The same corpus running against both finds divergences in either at no cost
to either side. `script/mvn_oracle/corpus.edn` and `run.clj` are the bb half.
