# ADR 0011: tools.deps in the native image

Status: experiment. The branch builds, and `babashka.deps/add-deps`, `-Sdeps`
and bb.edn `:deps` resolve Maven and git deps in-process, without a JVM.
Shipping is not decided.

## Question

bb shells out to a JVM for dependency resolution. `babashka.deps` runs deps.clj,
which runs `java`. Machines without a JDK cannot use `bb --deps`, `:deps` in
bb.edn, bbin or neil.

tools.deps 0.31.x dropped Sisu and Guice for MIMA `StandaloneStaticRuntime`.
That removes the dependency injection container that made Aether hard to build
with native-image. This experiment measures what tools.deps costs inside bb.

## Wiring

A `feature/tools-deps` lein profile with tools.deps 0.31.1638, the s3
transporter excluded. `BABASHKA_FEATURE_TOOLS_DEPS` gates it. The feature
namespace `babashka.impl.tools-deps` exposes 11 vars of `clojure.tools.deps`
to sci.

## Measurements

GraalVM 25.1.3, darwin aarch64.

| build | size | code area | units | heap | heap objects |
| --- | --- | --- | --- | --- | --- |
| baseline | 70.02 MB | 28.84 MiB | 74,306 | 40.13 MiB | 547,067 |
| tools.deps | 103.92 MB | 36.00 MiB | 92,538 | 66.56 MiB | 1,132,463 |
| tools.deps, specs stubbed | 75.02 MB | 31.08 MiB | 79,391 | 42.81 MiB | 575,286 |
| same, in-process make-classpath | 75.07 MB | 31.13 MiB | 79,482 | 42.81 MiB | 576,090 |

Reachable types: 21,587 baseline, 27,428 with tools.deps, 23,423 with the stub.

The uberjar grows from 23.83 MB to 28.30 MB.

Resolving `medley/medley 1.3.0` takes 20 ms. `clojure -Sforce -Sdeps ... -Spath`
takes 504 ms for the same map. A forced `add-deps` of the same map through the
in-process hook takes 33 ms wall for the whole bb invocation.

## In-process make-classpath

`babashka.impl.deps/add-deps` drives deps.clj, which keeps the cache-key
hashing, `.cpcache` staleness check, `-Sforce` and alias handling. Its one JVM
step is `*aux-process-fn*` running
`clojure.main -m clojure.tools.deps.script.make-classpath2 ...`. With the
feature on, bb binds that var to a function that recognises the
`make-classpath2` command and calls `babashka.impl.tools-deps/make-classpath!`
with the arguments after it. That function parses them with
`make-classpath2/parse-opts` and calls `make-classpath2/run`, which writes the
same cache files the subprocess would. Both are public, and `System/exit` lives
only in `-main`. Other scripts, `-Spom` and `-Stree`, still go to `java`.

Three details. deps.clj passes `--config-user nil`, which a subprocess would
stringify to an empty string and `blank-to-nil` would drop, so the arguments are
`str`ed first. Relative file arguments are resolved against the project dir,
and `clojure.tools.deps.util.dir/*the-dir*` is rebound to it with `with-dir`,
because the image bakes the builder's cwd into that var. deps.clj used to
resolve `java` at startup and throw when it was missing. It now looks java up
when a process starts, so the hook needs no workaround for it. deps.clj still
downloads the Clojure tools jar when it is missing, which the hook never uses.
Making that lazy belongs in deps.clj too.

`babashka.deps/clojure` binds the same function, built by
`babashka.deps/aux-process-fn`, so `bb clojure -Spath` and `-Stree` resolve
in-process too. Only starting clojure.main spawns `java`. When no java is
found the process functions raise deps.clj's own message through
`borkdude.deps/check-java-cmd!` instead of spawning an empty string.

Verified with `JAVA_CMD=/nonexistent/java`, which makes any subprocess fail:
`add-deps` with `:force true`, `-Sdeps`, bb.edn `:deps` through `--config`, and
a `:git/sha` dep all resolve and load. Verified again with `PATH=/bin` and no
`JAVA_HOME`, where deps.clj finds no java at all: `add-deps`, `-Sdeps` and
`babashka.deps/clojure` with `-Sforce -Spath` resolve.

## Image resources

`clojure.tools.deps.edn/root-deps` reads `clojure/tools/deps/deps.edn` through
`clojure.java.io/resource`, which uses the thread context classloader. bb sets
that to its own `URLClassLoader`, whose `getResource` only delegates JLine paths
to a parent. The resource is in the image and the parent loader returns it.
`make-classpath!` runs tools.deps with the context classloader swapped to the
parent for the duration of the call. tools.deps loads no user classes, so it
loses nothing.

Editing `impl-java/src-java/babashka/impl/URLClassLoader.java` does nothing
for a local build. That directory is the source of the published
`org.babashka/babashka.impl.java` artifact, which `project.clj` pins.

## The cost is clojure.spec, not Maven

`clojure.tools.deps.edn` requires `clojure.tools.deps.specs`, which is built on
`clojure.spec.alpha`. bb excludes spec by default. Making spec reachable
cascades into libraries bb already ships but keeps mostly unreachable.

Code area deltas against baseline, in build-report bytes:

| package | without stub | with stub |
| --- | --- | --- |
| taoensso | +977,415 | +8 |
| clojure.spec | +578,303 | +0 |
| clojure.tools.analyzer | +536,533 | +0 |
| clojure.core.async | +353,155 | +52 |
| org.ow2.asm | +284,934 | +0 |

`clojure.tools.deps.edn` calls two functions from that namespace,
`valid-deps?` and `explain-deps`. `feature-tools-deps/clojure/tools/deps/specs.clj`
shadows the namespace with a stub that has no spec dependency. Source paths
come before jars, so bb's copy wins at AOT time. That file is 15 lines and
removes 28.9 MB.

Maven itself is cheap. After the stub the remaining 5.00 MB is:

| package | bytes |
| --- | --- |
| org.apache.maven | 902,787 |
| org.apache.maven.model | 653,574 |
| org.apache.http | 551,120 |
| org.eclipse.aether | 437,356 |
| org.codehaus.plexus.util.xml.pull (xpp3) | 309,609 |
| org.codehaus.plexus | 255,190 |
| com.google.gson | 177,110 |
| clojure.tools.deps | 169,954 |

tools.deps' own Clojure code is 170 KB. An earlier reading attributed 1.1 MB to
it. That number was `clojure.tools.analyzer` sharing the `tools` label in the
report tree.

## Interpreted variant: same size

Branch `tools-deps-sci`. tools.deps, tools.deps.edn and tools.gitlibs ship as
source under `resources/src/babashka/clojure/tools`, the place bb already
keeps spec.alpha and test.junit, and sci interprets them. Nothing compiled
requires tools.deps. The Java classes the sources touch are registered in
`classes.clj`: `tools-deps-methods` lists, per class, the public methods whose
names the sources use, with parameter types per overload, and `<init>` where
the sources construct the class. `tools-deps-name-only` holds the five types
that are only referenced. Both are generated, see the scripts in the ADR
directory. `clojure.tools.deps.specs` is a built-in sci namespace with the two
stubbed functions. `TransferListener` is reified by the sources at load time
and is not in bb's reify registry, so the feature namespace supplies a compiled
adapter as a fallback for sci's `:reify-fn`.

| registration | size | code area | reachable types |
| --- | --- | --- | --- |
| name only, by mistake | 70.71 MB | 28.88 MiB | 21,838 |
| all public methods | 75.15 MB | 31.14 MiB | 23,192 |
| used methods only | 74.99 MB | 31.00 MiB | 23,187 |
| same, model classes all public | 75.05 MB | 31.06 MiB | |

The last row is the working configuration. `add-deps` with `:force true`,
`-Sdeps`, bb.edn `:deps`, a `:git/sha` dep and `babashka.deps/clojure` with
`-Sforce -Spath` on bb's own deps.edn all resolve natively, with no java on
the machine. A forced `add-deps` of medley takes 51 ms wall against 33 ms
compiled, the difference being sci loading the tools.deps sources.

The 70.71 MB build had the Maven classes registered without members, so the
Maven code was not in the image and resolution failed at the first method
call. With the classes registered properly the interpreted variant costs the
same as the compiled one, 75.07 MB. Narrowing to the methods used saves 160
KB, and the model classes take 60 KB of that back. The 5 MB is the Maven code that tools.deps executes, and how tools.deps
itself is packaged does not move it.

The used-methods lists cover what the sources call, not what Maven calls on
itself. Resolving bb's own deps.edn, a larger POM graph than medley's, reaches
`StringVisitorModelInterpolator`, which reflects over the model getters, and
failed on `Model.getDescription`. The `org.apache.maven.model` classes get
`allPublicMethods` for that, by hand, next to the generated lists.

Two GraalVM details cost a build each. A `:methods` entry without
`parameterTypes` means the zero-argument overload under
`--future-defaults=all`, and the build fails when there is none. And the xml
class splice near the end of the `classes` map is in `:instance-checks`,
which registers a name and nothing else.

## Ruled out: run-time resolve

Run-time `resolve` or `requiring-resolve` in bb code makes the Clojure compiler
reachable and has cost 30 MB before. That is not what happened here.
`clojure.lang.Compiler` goes from 16,017 to 75,969 bytes against a total code
area delta of 7.16 MiB, and it is present in the baseline binary already.

tools.deps loads its extensions with top-level `(load ...)`, which runs at build
time. Its only `requiring-resolve` sits inside a string passed to a `java -cp`
subprocess.

## Build flags

```
--initialize-at-build-time=org.slf4j,com.google.gson,org.eclipse.aether.util.version.GenericVersionScheme
-H:IncludeResources=org/apache/maven/model/pom-4.0.0.xml
-H:IncludeResources=clojure/tools/deps/deps.edn
```

Narrowing `--initialize-at-build-time` from whole packages to single classes
changed the binary by 0.09 MB. Blunt package init is not a size problem here.

## MIMA runtime lookup

`clojure.tools.deps.util.maven/make-context` calls `Runtimes/INSTANCE.getRuntime()`,
a ServiceLoader lookup. native-image does not register the provider and the call
throws `No Runtime implementation found on classpath`. Only
`StandaloneStaticRuntime` ships, so `babashka.impl.tools-deps` binds the private
`the-runtime` delay to it with `alter-var-root` at build time.

A supported way to select the runtime belongs upstream.

## Tradeoffs and open work

The specs stub skips deps.edn validation. A malformed deps.edn gets a worse
error instead of a spec explanation. The upstream fix is to load specs lazily in
`clojure.tools.deps.edn` and pull spec in only to explain a failure.

The `clojure.tools.deps` vars exposed to scripts are plain copies and do not
swap the context classloader, so a git dep resolved by calling
`clojure.tools.deps/resolve-deps` directly still needs the root deps.edn on the
script classpath. `add-deps` and friends do not.

Direct linking is on for the uberjar, so patching `root-deps` with
`alter-var-root` cannot reach the compiled call site.

## Reproducing

```bash
BABASHKA_FEATURE_TOOLS_DEPS=true script/uberjar
BABASHKA_FEATURE_TOOLS_DEPS=true script/compile
JAVA_CMD=/nonexistent/java ./bb -e "(babashka.deps/add-deps '{:deps {medley/medley {:mvn/version \"1.3.0\"}}} {:force true}) (require '[medley.core :as m]) (prn (m/map-vals inc {:a 1}))"
JAVA_CMD=/nonexistent/java ./bb -Sdeps '{:deps {medley/medley {:mvn/version "1.3.0"}}}' -e "(require '[medley.core :as m]) (prn (m/map-keys name {:a 1}))"
```

Package attribution comes from `script/compile --emit build-report`. Extract
label and value pairs from the HTML and diff two builds. ADR 0007 covers the
JSON report route for totals.

Branch `mvn-clj` carries an oracle harness for the Clojure Maven procurer
that will replace the Java stack. `script/mvn_oracle/oracle.clj` resolves
one corpus entry with tools.deps and prints versions, repo-relative paths,
dependents and per-artifact `coord-deps` as EDN. It runs under `./bb` and
under `clojure`. `script/mvn_oracle/run.clj` runs both and diffs:

```bash
bb script/mvn_oracle/run.clj            # every corpus entry, warm repo
bb script/mvn_oracle/run.clj --cold medley guava   # bb downloads, JVM must accept
```

The corpus in `script/mvn_oracle/corpus.edn` holds deps.edn snapshots of
babashka, clj-kondo, quickblog and clojure-lsp, plus libraries with hard
POMs. With the Aether-backed tools.deps on both sides all 17 entries match,
warm and cold, which is the harness's own check.

The floor for the procurer, measured on this branch with the Maven
registrations in `classes.clj` switched off and nothing else changed:

| build | size | code area | reachable types |
| --- | --- | --- | --- |
| baseline | 70.02 MB | 28.84 MiB | 21,587 |
| interpreted tools.deps, no Maven | 70.05 MB | 28.85 MiB | 21,610 |

The bundled sources, the reify adapter and the load-fn patches cost 30 KB.
Everything else in the 5 MB is Maven. A Clojure procurer adds its own source
on top of 70.05 MB.

On this branch the bundled sources get their patches from the load-fn:
`babashka.impl.tools-deps/patch-source` appends the MIMA runtime binding to
`util/maven.clj` and the embedded root deps.edn to `edn.clj` as they are
served, so a script that requires `clojure.tools.deps` directly works too.

To regenerate the reflection lists on the `tools-deps-sci` branch after a
tools.deps bump, from the repository root:

```bash
A=doc/adr/0011-tools-deps-in-native-image
mkdir -p target/tools-deps
bb $A/method-names.clj > target/tools-deps/method-names.txt
clojure -Sdeps '{:deps {org.clojure/tools.deps {:mvn/version "0.31.1638"}}}' -M $A/precise-methods.clj > target/tools-deps/precise.edn
bb $A/render-defs.clj
bb $A/replace-defs.clj
```

The first script lists the method names the sources call. The second keeps,
per registered class, the public methods with those names, with parameter
types. The last two render them as the two defs and replace those in
`classes.clj`. A class the sources start using has to be added to one of the
defs by hand first, the scripts take the class list from there.
