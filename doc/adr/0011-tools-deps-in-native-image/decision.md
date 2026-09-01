# ADR 0011: tools.deps in the native image

Status: experiment. The branch builds and resolves deps. Shipping is not decided.

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

Reachable types: 21,587 baseline, 27,428 with tools.deps, 23,423 with the stub.

The uberjar grows from 23.83 MB to 28.30 MB.

Resolving `medley/medley 1.3.0` takes 20 ms. `clojure -Sforce -Sdeps ... -Spath`
takes 504 ms for the same map.

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

Git deps need the `clojure/tools/deps/deps.edn` resource, read through
`clojure.java.io/resource`. bb's `URLClassLoader` restricts parent delegation of
resource lookups to JLine paths. Adding the tools.deps prefix to that allowlist
did not help, because the fallback loader does not have the resource either.
Putting the file on the script classpath with `-cp` makes git deps resolve, so
the remaining work is making that one 478 byte resource visible.

Direct linking is on for the uberjar, so patching `root-deps` with
`alter-var-root` cannot reach the compiled call site.

## Reproducing

```bash
BABASHKA_FEATURE_TOOLS_DEPS=true script/uberjar
BABASHKA_FEATURE_TOOLS_DEPS=true script/compile
./bb -cp <dir with clojure/tools/deps/deps.edn> -e "(require '[clojure.tools.deps :as deps]) (prn (keys (deps/resolve-deps '{:deps {medley/medley {:mvn/version \"1.3.0\"}} :mvn/repos {\"central\" {:url \"https://repo1.maven.org/maven2/\"} \"clojars\" {:url \"https://repo.clojars.org/\"}}} nil)))"
```

Package attribution comes from `script/compile --emit build-report`. Extract
label and value pairs from the HTML and diff two builds. ADR 0007 covers the
JSON report route for totals.
