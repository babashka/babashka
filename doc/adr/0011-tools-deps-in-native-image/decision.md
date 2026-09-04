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

## Ruled out: the compiler

Run-time `resolve` or `requiring-resolve` in bb code makes the Clojure compiler
reachable and has cost 30 MB before. That path is not what happened here; the next section has the one that did.
`clojure.lang.Compiler` goes from 16,017 to 75,969 bytes against a total code
area delta of 7.16 MiB, and it is present in the baseline binary already.

tools.deps loads its extensions with top-level `(load ...)`, which runs at build
time. Its only `requiring-resolve` sits inside a string passed to a `java -cp`
subprocess.

## The trigger: a run-time read of Namespace.mappings

The single call that grows the image is the `resolve` in spec's `res`
helper, the one that qualifies symbols for `describe` output. Bisected on
branch `spec-dynaload-exp` with the real `clojure.tools.deps.specs` in every
build and spec.alpha 0.5.238 swapped for a jar compiled from patched sources.
Every build below is identical apart from the named function.

| build | change                                                       | image     | types  |
|-------|--------------------------------------------------------------|-----------|--------|
| A     | none                                                         | 103.97 MB | 27,477 |
| B     | `gen/dynaload` throws                                        | 103.95 MB | 27,468 |
| C     | B, and `exercise-fn` no longer resolves its symbol           | 103.95 MB | 27,476 |
| E     | C, and `res` no longer resolves                              | 76.01 MB  | 23,808 |
| G     | C, `res` calls `Compiler/maybeResolveIn` directly            | 103.95 MB | 27,476 |
| H     | C, `res` does `find-ns` plus `findInternedVar` or `getMapping` | 103.97 MB | 27,476 |
| I1    | C, `res` does only `find-ns`                                 | 76.03 MB  | 23,810 |
| I2    | C, `res` does only `(.getMapping *ns* form)`                 | 103.95 MB | 27,476 |

So it is not the run-time `require`, not `Class.forName`, not `find-ns`. It
is any read of a namespace's mapping table: `getMapping`, `findInternedVar`,
and through them `ns-resolve`, `resolve`, `ns-publics`, `ns-map`, `var-get`
on a looked-up symbol, and so on.

Why that read costs 28 MB: native-image follows an instance field during heap
scanning only when reachable code reads that field. bb never reads
`Namespace.mappings` at run time, because sci keeps its own registry, so the
Clojure vars in those maps and the function objects in their roots are dead
heap. One reachable read makes every var of every loaded namespace live, every
function object counts as instantiated, and every megamorphic `IFn.invoke`
site now has to compile them all. That is the timbre, core.async,
tools.analyzer and ASM code in the delta, and the 26 MB of extra heap.

The recipe for such a bisection: `spec-experiment/spec-variants.clj` writes
the patched sources, `spec-exp-run.sh <label> <variant>` builds the jar and
the image. Shadowing the namespace from a source path does not work. The jar
ships AOT classes, and a source copy gets evaluated during Clojure's own
startup, where the `ns` spec check cycles into the half-loaded
`clojure.spec.alpha`. The replacement jar is compiled with
`clojure.lang.Compile`, and its `.clj` entries are stamped 2024 so the classes
are strictly newer; jar timestamps have two-second resolution, and Clojure
loads the source when it is not older.

## Workaround: keep the mapping tables out of the image

Two build-time mechanisms fix this generically, without touching spec or any
other library, and both leave build-time `resolve` intact because they only
change what goes into the image. Both measured with the real
`clojure.tools.deps.specs` and the upstream spec.alpha jar.

| build | mechanism                                                   | image    | types  |
|-------|-------------------------------------------------------------|----------|--------|
| S1    | substitution: `mappings` reset, every reader throws         | 77.31 MB | 23,944 |
| S2    | feature: `mappings` pruned to the vars the image holds      | 77.31 MB | 23,945 |

S1 is `spec-experiment/Target_clojure_lang_Namespace.java`: an `@Alias` on
`Namespace.mappings` with `RecomputeFieldValue(Reset)`, and `@Substitute`
bodies for `getMapping`, `findInternedVar`, `intern`, `refer`, `reference`,
`referenceClass`, `importClass`, `unmap` and `getMappings` that throw an
`UnsupportedOperationException` naming the symbol and namespace. A run-time
lookup fails loudly, in the test suite, instead of adding 28 MB to a release.

S2 is `src-java/babashka/impl/NamespaceFeature.java`, enabled with
`--features=babashka.impl.NamespaceFeature`. Compiled Clojure keeps every var
it references in a `static final Var const__N` field of the referencing
class. The feature walks the uberjar before analysis, finds 21,347 such
fields, and registers a reachability handler on each; a handler fires when
reachable code reads its field, and records the var. A field value
transformer on `Namespace.mappings` reports itself unavailable until
`afterAnalysis`, so the analysis assumes nothing about the table's contents,
and then replaces each table with a persistent map of that namespace's
recorded vars: 1,903 vars in 225 namespaces. Those objects are in the image
regardless, so the tables add nothing.

With S2 a run-time `resolve` in the binary answers correctly for what is
there. Probed through a hook in `main` that resolves a symbol from the
environment (`spec-experiment/add-probe.clj`):

```
clojure.core/*ns*                :probe #'clojure.core/*ns*
clojure.core/inc                 :probe #'clojure.core/inc
clojure.string/blank?            :probe #'clojure.string/blank?
clojure.tools.deps/resolve-deps  :probe #'clojure.tools.deps/resolve-deps
clojure.spec.alpha/valid?        :probe #'clojure.spec.alpha/valid?
clojure.core/unchecked-add-int   :probe nil
taoensso.timbre/info             :probe nil
clojure.core.async/go            :probe nil
nope/nope                        :probe nil
```

sci's `resolve` and `find-ns` in scripts use sci's own registry and are
unaffected in both builds.

### The remaining 1.3 MB is run-time require

S1 and S2 sit 1.3 MB above E, the build with spec's three run-time lookups
stubbed at the source. Bisected the same way, on top of the substitution:

| build | change                                                     | image    | types  |
|-------|------------------------------------------------------------|----------|--------|
| S1    | substitution, spec untouched                               | 77.31 MB | 23,944 |
| S3    | S1, `gen/dynaload` throws                                  | 76.03 MB | 23,832 |
| S4    | S1, `RT.load` substituted to throw                         | 76.57 MB | 23,860 |
| S5    | S4, `clojure.core/require`, `use`, `load` substituted too  | 76.05 MB | 23,834 |

So once the mapping tables are fixed, the two `resolve` calls cost nothing
and the whole 1.3 MB is the `require` in `dynaload`: `RT.load` and the
reader and compiler behind it, about 0.7 MB, and the `load-libs`,
`load-lib`, `load-one` plumbing above it in clojure.core, about 0.5 MB.
`Target_clojure_lang_RT.java` and `Target_clojure_core_require.java` close
both, at the entry points, with the same loud error. Every run-time
`require` in bb's compiled code would be a bug anyway; scripts require
through sci.

With all three substitutions an untouched spec.alpha with the real specs
namespace costs 1 MB over the stubbed build, which is spec's own code.

What S2 does not cover: a var whose only reference is inside another heap
object rather than a static field, and class mappings, which the pruned
table drops, so `(resolve 'String)` gives nil in the image. Both could be
added. The remaining 1.3 MB over the stubbed build is the resolve machinery
itself, now reachable but cheap. The two mechanisms compose: S2's table plus
a substituted `getMapping` that throws for a symbol the build knew and
dropped would give correct answers and loud failures.

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
