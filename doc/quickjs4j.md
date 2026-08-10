# QuickJS in babashka via quickjs4j

Experiment: embed a JS engine in bb so that squint-compiled libraries
(@babashka/cli, @babashka/fs and friends) and other npm libraries run
inside bb scripts.

## Why quickjs4j

quickjs4j runs QuickJS compiled to WebAssembly on
[Chicory](https://github.com/dylibso/chicory), a pure-Java Wasm
runtime. No JNI, no per-platform shared libraries, and Chicory
supports GraalVM native-image. Chicory can AOT-translate the wasm to
JVM bytecode, which avoids interpreter overhead and plays well with
native-image closed-world analysis.

- quickjs4j: https://github.com/roastedroot/quickjs4j
- Chicory: https://github.com/dylibso/chicory

Rejected alternatives:

- GraalJS: Truffle language in a native image costs tens of MB and
  build complexity (see the graaljs-cherry experiment).
- Rhino: pure Java and native-image friendly, but the ES level is too
  low for esm.sh output without downleveling everything to es2017.
- Nashorn: ES5 era, invokedynamic heavy, poor native-image story.
- Javet/J2V8/quickjs JNI wrappers: per-platform native libs.

## Spike plan

1. JVM-only first: add quickjs4j to a scratch deps.edn, eval `1 + 2`,
   then load squint core (npm package `squint-cljs`, file
   `src/squint/core.js`) and call a few fns.
2. Run a squint-compiled lib: feed it @babashka/cli's `cli.mjs` plus
   squint core and call `parse-args`. Both are ES modules: check what
   module support quickjs4j exposes (QuickJS itself has full ESM; the
   binding layer must expose a module loader hook).
3. Wire a module loader in Java (see resolver notes below).
4. native-image: compile the scratch project with the bb build flags,
   measure binary size delta and startup. Chicory AOT mode preferred.
5. Decide: bb feature flag (`feature-quickjs`?), pod, or drop.

## What choq learned (port these one to one)

Choq (github.com/squint-cljs/choq) embeds QuickJS via Rust and runs
cherry/squint libs from esm.sh. The platform layer it needed:

- Module resolver: bare/`node:` specifiers for builtins, https urls
  downloaded and cached (`~/.cache/choq`, sha256 of url as key),
  relative specifiers resolved against the importing module's url.
- Download with user agent `Node.js/22.0.0`: esm.sh then serves node
  builds (node export conditions) instead of browser builds. Without
  it, libs like chalk detect color support via `navigator` and break.
- esm.sh serves node builtins as `/node/<name>.mjs` shim urls inside
  remote modules: map those back to the host's builtin modules.
- Builtins actually needed by real libs so far: `fs` (existsSync!),
  `path`, `buffer`, `net`, `tty` (isatty), `crypto`, `os`, `process`
  (env, nextTick), `zlib` (@babashka/fs pulls it), `timers`,
  `stream`, `events`, `string_decoder`. The last three are pure-js
  (vendored readable-stream + events + string_decoder bundles from
  esm.sh) and can be reused as-is. In bb these become Java-backed
  host functions instead of LLRT Rust modules.
- Polyfills required: `globalThis.global = globalThis`,
  `process.nextTick` -> `queueMicrotask`, `Buffer.indexOf` with
  string/Buffer needles (llrt gap; quickjs4j will need its own Buffer
  story, possibly the npm `buffer` package).
- Default stack: QuickJS default (256KB) is too small for compiler
  workloads; choq sets 4MB. Windows main thread needs an explicit
  16MB thread stack.
- Sub-dependencies: esm.sh resolves semver ranges at fetch time, so
  two libs sharing a dep only share one instance when both ranges
  resolve to the same concrete version. Squint tolerates duplicates
  (its data is native js), cherry does not (cljs.core class
  instances). Pin or lock for determinism.

## Open questions

- quickjs4j API surface: module loader hook, host function
  registration, bytecode caching, TypedArray interop.
- Wasm QuickJS performance under Chicory interpreter vs AOT vs the
  native QuickJS numbers from choq (startup ~18ms, hono ~30k req/s;
  bb targets are much more modest: run cli/fs-style libs).
- WASI/filesystem: how quickjs4j exposes the host fs to the engine,
  or whether all io goes through host functions.
- Data conversion: js values <-> Clojure values at the bb boundary
  (squint's native-js data makes this mostly json-shaped).

## Session context

Design discussion happened in the choq repo sessions (2026-08-09/10).
Choq source is the reference implementation: `src/main.rs` (resolver,
loader, polyfills, url cache), `src/serve.rs` (host function
pattern), `vendor/` (pure-js builtin bundles).
