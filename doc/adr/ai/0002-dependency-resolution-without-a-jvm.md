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
   in-process run when the resolver is `bb` or unset, and leave it alone
   when it is `jvm`; bb became the default on 2026-09-10. `:deps-resolver` in the deps map says it, then the one
   in bb.edn, which a task's `:extra-deps` inherit; without either
   `BABASHKA_DEPS_RESOLVER` decides, read through the resolve's
   environment view, so `:env` and `:extra-env` on `add-deps` and
   `clojure` count, and a test can run both paths in one process.
3. `babashka.impl.tools-deps`, compiled: requires `make-classpath2` into the
   script's sci context, absolutizes the file paths deps.clj hands over, and
   runs `parse-opts` and `run` under `with-dir`, one call at a time. For
   the run, tools.deps' `user-config-dir` answers the config dir deps.clj
   found in the call's environment, so a named tool's descriptor comes
   from the call's `CLJ_CONFIG`, `-Srepro` or not; gitlibs points at the
   call's `GITLIBS`; and `babashka.impl.mvn.env/getenv`, the one place the
   procurer reads the environment for `${env.NAME}` in settings.xml and
   POMs, `http_proxy`, `no_proxy` and `CLOJURE_CLI_ALLOW_HTTP_REPO`, is
   the call's lookup. All three are process-wide state, hence the lock.
   Also holds the specs stub.
4. tools.deps 0.31.1638, tools.deps.edn 0.9.42 and gitlibs 2.6.217, bundled
   as source under `resources/src/babashka/clojure/tools/` and interpreted by
   sci: `make-classpath2`, the basis, `expand-deps`, the session cache, the
   extension multimethods. 16 namespaces verbatim. Four namespaces that
   import Maven classes when they load have stand-ins at their own paths,
   marked `BB-STAND-IN` in their docstrings. Two files carry patches
   between `BB-PATCH` and `END-BB-PATCH` markers, the upstream form kept
   under `#_` next to each: `local.clj`, whose `:jar` methods read the POM
   as text, and `edn.clj`, whose `root-deps` returns the root deps.edn as
   data, since the image cannot see the jar resource. The vendor script,
   `script/vendor_bundled_sources.clj`, copies only the files it lists, writes
   the `edn.clj` patch itself, and reports every other file in the jars, so
   an upgrade shows each upstream addition. One grep for the two markers
   lists every deviation.
5. `babashka.impl.mvn`, 12 namespaces, 1,643 lines, plain Clojure over
   babashka.fs, babashka.http-client, data.xml and javax.crypto. The `:mvn`
   and `:pom` procurer registered through tools.deps' `ext/` multimethods,
   required from the stand-in `extensions/maven.clj`, which tools.deps loads
   last.

Inside `babashka.impl.mvn`:

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

bb and the JVM share `~/.m2/repository`. So `babashka.impl.mvn` writes what Maven
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
- tools.deps's own test suite at the bundled version, run by
  `tools_deps_test.clj` in the library's checkout: 62 tests, the resolution
  algorithm over a fake Maven repository, local POM deps, git deps, the
  classpath script. Its test helper imports Aether's version scheme and is
  loaded with that swapped for bb's port. The first run found one
  difference: tools.deps reads build-helper `add-resource` directories in a
  text form the plugin rejects, bb read only the documented form; bb reads
  both now.
- Maven's own unit tests, ported case by case into scripts next to the
  others: the four profile activators (128 cases), the model interpolator
  (the cases about versions, properties and urls), the version range and
  constraint parser, the update policy analyzer, the snapshot version
  resolver. Porting them rewrote the activators after Maven's (jdk ranges
  by Maven's three-token compare, `regex:` os versions, unlisted os
  families such as `linux`, empty properties), the range parser after
  GenericVersionRange (wildcards, malformed ranges rejected), and `daily`
  after DefaultUpdatePolicyAnalyzer (local midnight, not 24 hours).
  The last batch: the repository layout, checksum sidecars, mirror
  selection, nonProxyHosts and the plexus cipher vectors. It rewrote the
  mirror selector after DefaultMirrorSelector (a mirror naming the
  repository id wins over an earlier pattern, `mirrorOfLayouts`,
  `localhost` as a host rather than a substring) and the sidecar reader
  after ChecksumUtils (the `name = sum` form, `.md5` when a repository
  publishes no `.sha1`).
- CI runs the scripts on the built binary on Linux, macOS and Windows, and
  the JVM suite runs one resolve under each value of the switch.

## Decisions taken along the way

- No build feature. It cost nothing in the image and an untested "off"
  configuration goes stale.
- `jvm` stays the default for the first release, `bb` is a dated flip
  after it, and the same variable is the way back.
- deps.edn validation is skipped: the specs namespace is a stub, since
  clojure.spec stays out of the image. Accepted.
- S3 repositories: not supported.
- Maven 4 password blobs: neither side reads them; tools.deps sits on the
  same plexus-sec-dispatcher 2.0.
- Failure messages are worded like the CLI's, since a user compares the two.
- The git that gitlibs spawns inherits bb's process environment, not the
  call's, so `GIT_SSH_COMMAND` or `GITLIBS_COMMAND` in `:extra-env` reach
  git under `jvm` and not under `bb`. gitlibs builds the process itself,
  and a patch there is not worth one variable; the process environment is
  the place for it.
- A jar a namespace was loaded from cannot be deleted on Windows while the
  process runs, and that stays so. `URLClassLoader` keeps a `JarFile` open
  per jar for its own lifetime, and a `jar:` URL read through the JDK's
  connection cache keeps another; Windows refuses to delete an open file,
  Linux and macOS do not care. Clojure on the JVM behaves the same, for the
  same reason, and `URLClassLoader.close()` exists because of it. Reading
  with the cache off was tried and reverted: it cost about 0.45 ms per
  namespace loaded from a jar, measured on the clojure jar, and left the
  loader's own handle in place anyway. So the oracle tests that require from
  a resolved jar keep their `local-repo` outside the directory they delete,
  which is what tools.deps does with `~/.m2` as well.

## What is borrowed, and how it is credited

tools.deps unchanged but for the stand-ins and two marked patches, all named
in the NOTICE next to the copies. `babashka.impl.mvn` ports behaviour from Maven and
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

| | Grenadine | babashka.impl.mvn |
|---|---|---|
| Source | 5,326 lines, 17 namespaces, plus a 1,227-line CLI | 1,643 lines, 12 namespaces |
| Tests | 2,079 lines; an oracle against tools.deps 0.31.1642 | 499 assertions in scripts; an oracle against tools.deps 0.31.1638, warm and cold, 22 entries |
| tools.deps core | ported: `deps.clj` from `excluded?` to `expand-deps` becomes `expander.cljc` and `basis.cljc`, gitlibs becomes `gitlibs.cljc`, by unified patches over pinned revisions with SHA-256 ledgers | unchanged: 17 namespaces verbatim, interpreted by sci; four stand-ins for the Maven-bound namespaces; one patch, the root deps.edn |
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

- The corpus at scale, 239 roots, runs offline after procurer changes;
  each finding becomes a test in `script/mvn_oracle`. First run 2026-09-08:
  one bug, duplicate dependency declarations in a flattened POM.
- Effective-POM and effective-settings oracles against Maven's own
  `help:effective-pom` and `help:effective-settings`.
- A Maven-literate human review.
- The ask to tools.deps: load the two Maven-backed extensions only when
  Maven is on the classpath, so the stand-ins can go and the procurer can be
  a library. The load is at `clojure/tools/deps.clj` lines 759 to 764 of
  v0.31.1638.
