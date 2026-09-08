# ADR 0002: dependency resolution without a JVM

## Status

Recorded 2026-09-08 on branch `mvn-clj`. The measurements that led here are
in ADR 0011, `doc/adr/0011-tools-deps-in-native-image/decision.md`; this
document describes the result.

## Context

`babashka.deps/add-deps`, `-Sdeps`, bb.edn `:deps` and `bb clojure` resolve
Maven and git coordinates through deps.clj, which spawned a java to run
tools.deps' `make-classpath2`. A machine without a JVM could not use any of
them. tools.deps itself is Clojure and small; what needs the JVM is Maven
Resolver underneath it, and compiling that into the image costs 5 MB. So the
design keeps tools.deps and replaces Maven.

## Architecture

Layers, top to bottom, with what is ours and what is not:

1. deps.clj, the Clojure CLI port bb embeds. Parses the command line, keeps
   the `.cpcache`, decides when the classpath is stale. Three changes on its
   `lazy-java-lookup` branch: java is looked up when a process starts, the
   Clojure tools jar is installed when a process needs it, and the classpath
   refresh goes through a new dynamic var, `*make-classpath-fn*`. The
   spawned-java behaviour is the default of that var, so existing users of
   deps.clj as a library, Cursive among them, see no change.
2. `babashka.deps` and `babashka.impl.deps` bind `*make-classpath-fn*` to an
   in-process run when `BABASHKA_DEPS_RESOLVER` is `native`, and leave it
   alone when it is `jvm` or unset. The variable is read through the
   resolve's environment view, so `:env` and `:extra-env` on `add-deps` and
   `clojure` count, and a test can run both paths in one process.
3. `babashka.impl.tools-deps`, compiled: requires `make-classpath2` into the
   script's sci context, absolutizes the file paths deps.clj hands over, and
   runs `parse-opts` and `run` under `with-dir`. Also holds the specs stub
   and the one source patch.
4. tools.deps 0.31.1638, tools.deps.edn 0.9.42 and gitlibs 2.6.217, bundled
   as source under `resources/src/babashka/clojure/tools/` and interpreted by
   sci: `make-classpath2`, the basis, `expand-deps`, the session cache, the
   extension multimethods. 36 files verbatim. Four namespaces that import
   Maven classes when they load have stand-ins at their own paths;
   `NOTICE.md` next to them says which and why. The root deps.edn, a jar
   resource the image cannot see, is vendored as a file and appended to
   `edn.clj` as data when the load-fn serves it. `script/vendor_tools_deps.clj`
   makes the copies.
5. `babashka.mvn`, 12 namespaces, 1,643 lines, plain Clojure over
   babashka.fs, babashka.http-client, data.xml and javax.crypto. The `:mvn`
   and `:pom` procurer registered through tools.deps' `ext/` multimethods,
   required from the stand-in `extensions/maven.clj`, which tools.deps loads
   last.

Inside `babashka.mvn`:

- `coords`: lib names with `$classifier`, packaging types, the repository
  layout, and where Maven Resolver keeps a timestamped snapshot.
- `settings`: settings.xml, mirror selection after `DefaultMirrorSelector`,
  proxy selection after `DefaultProxySelector` with the env fallback deps.clj
  used to pass to java.
- `cipher`: the plexus legacy password format, master password and
  relocation, checked against vectors the JVM libraries produced.
- `http`: fetches with checksums per policy, one client per proxy with
  credentials, transport failures worded like Aether's.
- `repo`: remote repositories with mirror, auth and proxy applied, the local
  repository with `_remote.repositories` markers and a `_babashka.snapshots`
  sidecar, per-path locking, downloads from the first repository that has the
  file.
- `metadata`: `maven-metadata.xml` cached as `maven-metadata-<repo>.xml`
  under the update policy; `LATEST`, `RELEASE`, versions, snapshot builds.
- `version`: `GenericVersion` from maven-resolver-util, ranges.
- `pom`: the effective model, all five profile activators, inheritance,
  interpolation, BOM imports, dependency management, relocation,
  build-helper paths.
- `tools-deps`: the multimethods, `coord-deps`, `coord-paths`,
  `canonicalize`, `find-versions`, `compare-versions`, `license-info`, and
  the `:pom` manifest for git deps with a pom.xml.

What still spawns a java: `-Spom`, and the final process of `-M`, `-X`,
`-T` and the REPL, which is the point of those commands. `-X` and `-T`
install the Clojure tools first because they run `exec.jar` from it.

## Two local repositories, one directory

bb and the JVM share `~/.m2/repository`. So `babashka.mvn` writes what Maven
Resolver reads and reads what it writes: the `_remote.repositories` markers,
timestamped snapshots stored under their base name, metadata files named per
repository, `.sha1` sidecars verified on download. The oracle's `--cold`
mode wipes the shared repository, lets bb download, and then makes the JVM
accept what bb wrote.

## Verification

- `script/mvn_oracle/run.clj`: 22 corpus entries resolved by bb and by the
  JVM tools.deps, libs, coordinate deps and versions diffed, warm and cold.
  This is the only defense that scales; every reimplementation of Maven has
  bled on the long tail, and the oracle is where that shows first.
- Eight test scripts next to the harness, 499 assertions, run by `tests.clj`
  from the tree or against the bundled sources: the version scheme over the
  160 cases extracted from maven-resolver-util's own test, coordinates,
  settings, the POM model from in-memory POMs, the cipher against JVM
  vectors, failure messages, and two end-to-end resolves through an http-kit
  server, one behind basic auth and one behind an authenticating proxy for a
  host that does not resolve.
- CI runs the scripts on the built binary on Linux, macOS and Windows, and
  the JVM suite runs one resolve under each value of the switch.

## Decisions taken along the way

- No build feature. It cost nothing in the image and an untested "off"
  configuration goes stale.
- `jvm` stays the default for the first release, `native` is a dated flip
  after it, and the same variable is the way back.
- deps.edn validation is skipped: the specs namespace is a stub, since
  clojure.spec stays out of the image. Accepted.
- S3 repositories: not supported.
- Maven 4 password blobs: neither side reads them; tools.deps sits on the
  same plexus-sec-dispatcher 2.0.
- Failure messages are worded like the CLI's, since a user compares the two.

## What is borrowed, and how it is credited

tools.deps unchanged but for the stand-ins and the one patch, both named in
the NOTICE next to the copies. `babashka.mvn` ports behaviour from Maven and
its libraries where the behaviour is an algorithm, and says so in the
namespaces and in its own NOTICE, with the Apache License 2.0 text alongside.
The version scheme is the one tools.deps compares with, `GenericVersion`
from maven-resolver-util, not maven-artifact's `ComparableVersion`: `_` is a
separator in one and not the other, so `5.0_ALPHA` sorts below `5.0` on the
JVM. The first port made the wrong choice and the oracle caught it.

## Other resolvers without a JVM

Grenadine, "pomegranate with the JVM pressed out", from the ClojureStar
project, is a dependency resolver in portable Clojure for dialects without a
Java class library, Glojure and Jolt. It solves the same problem from the
other side. Its targets cannot run tools.deps, so it ports the core,
`expand-deps` and the basis, by unified patches over pinned upstream
revisions with checksum ledgers, and keeps a thin Maven layer underneath:
coordinates, POM model, metadata, sha1 sidecars, `LATEST`. It adds lock
files, three mediation strategies, a `require-deps` facade and its own CLI,
5,326 lines with 2,079 of tests and an oracle against tools.deps of its own.
It has no settings.xml support, and its version scheme adapts
`ComparableVersion`, the choice the oracle rejected here.

| | Grenadine | babashka.mvn |
|---|---|---|
| Source | 5,326 lines, 17 namespaces, plus a 1,227-line CLI | 1,643 lines, 12 namespaces |
| Tests | 2,079 lines; an oracle against tools.deps 0.31.1642 | 499 assertions in scripts; an oracle against tools.deps 0.31.1638, warm and cold, 22 entries |
| tools.deps core | ported: `deps.clj` from `excluded?` to `expand-deps` becomes `expander.cljc` and `basis.cljc`, gitlibs becomes `gitlibs.cljc`, by unified patches over pinned revisions with SHA-256 ledgers | unchanged: 36 files verbatim, interpreted by sci; four stand-ins for the Maven-bound namespaces; one patch, the root deps.edn |
| Maven layer | its own: coordinates, POM model, metadata, sha1 sidecars, `LATEST` | its own: the same, plus settings.xml with mirrors, servers, encrypted passwords, proxies with credentials |
| settings.xml | none | full, because `bb clojure` must answer as `clojure` does on the same machine |
| Version ordering | adapted from `ComparableVersion`, Apache Maven 3.9.16 | ported from `GenericVersion`, maven-resolver-util 1.9.27 |
| Beyond tools.deps | lock files, three mediation strategies, `require-deps`, a CLI, a facade across dialects | nothing; `make-classpath2` is the only entry |
| Portability | `.cljc`, host effects injected, no JVM classes | bb only: babashka.fs, babashka.http-client, data.xml, javax.crypto |

Same problem, opposite halves reimplemented: they redo the model and keep a
thin transport; bb keeps the model and redoes the transport.

bb can run tools.deps, so it replaced only the half bb cannot have. That is
why bb did not adopt Grenadine: a second implementation of `expand-deps` to
keep in step buys nothing when the original runs at 30 KB; bb's users have
settings.xml files the CLI honours, so `bb clojure` had to as well; and the
audiences barely overlap. Two Maven layers for two runtimes is not
duplication. One tools.deps core for two runtimes would have been worth
sharing, and only Grenadine needs it. What can be shared is the corpus and
the harness: the same corpus run against both finds divergences in either.

## Open

- Corpus at scale, the top few hundred Clojars and Central artifacts,
  nightly.
- Effective-POM and effective-settings oracles against Maven's own
  `help:effective-pom` and `help:effective-settings`.
- A Maven-literate human review.
- The ask to tools.deps: load the two Maven-backed extensions only when
  Maven is on the classpath, so the stand-ins can go and the procurer can be
  a library. The load is at `clojure/tools/deps.clj` lines 759 to 764 of
  v0.31.1638.
