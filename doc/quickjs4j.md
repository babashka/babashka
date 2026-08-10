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

## Spike results (2026-08-10)

Measured with quickjs4j 0.1.0 and GraalVM 25.0.2, macOS aarch64.

Binary size, both built from this tree with the default feature set:

| binary | bytes | MiB |
| --- | --- | --- |
| bb baseline | 72,522,200 | 69.16 |
| bb + quickjs4j | 82,957,816 | 79.10 |
| delta | +10,435,616 | +9.95 (+14.4%) |

The baseline reproduces `~/dev/babashka/bb` (72,522,224 bytes) to within 24
bytes, so that binary is a valid reference point.

An isolated Java hello-world costs more: 11,310,776 bytes without quickjs4j
and 24,891,560 with it, a delta of 13,580,784. Inside bb the delta is smaller
because jackson-core and parts of java.base are already reachable.

Image breakdown of the added code area: 4.26MB quickjs4j, 972kB
jackson-databind, 364kB jackson-core, 909kB endive runtime plus wasm plus
wasi. The rest is image heap growth, mostly code metadata.

Startup of `bb -e 1` is unchanged: 9.1ms min for both binaries. Nothing
quickjs4j-related is initialized until `babashka.js` is used.

Runtime in the native binary:

| operation | ms |
| --- | --- |
| engine build plus `console.log(1+2)` | 6.8 |
| compile bundled @babashka/cli (125kB source) | 34.0 |
| exec precompiled bytecode (294kB) | 5.6 |

Bundled @babashka/cli runs correctly. `parseArgs(['--foo','1','sub'])` returns
`{"args":["sub"],"opts":{"foo":1}}` and `parseOpts` honors `:coerce`.

Language level is fine for esm.sh output: class fields, private fields,
top-level await, `Array.prototype.at`, `Object.hasOwn`, named capture groups,
lookbehind, BigInt, TextEncoder, TextDecoder all work. Missing: `WeakRef`,
`FinalizationRegistry`, `structuredClone`, `fetch`, `process`, `Buffer`,
`require`.

### Blocker: no module loader

`import` fails for every specifier, bare and relative alike. The engine writes
`could not load module 'foo.js'` to stderr and is left unusable. `import()`
rejects with the same message.

The message is a Rust panic from the javy-plugin source, but nothing native
runs. The plugin is Rust compiled to wasm and then translated to JVM bytecode
by Endive at quickjs4j build time. The shipped jar holds only class files. The
panic text is a string constant carried in the wasm data section, printed
through WASI stderr by bytecode.

The failure is at compile time. `compileSrc` calls
`javy_plugin_api::compile_src(source).unwrap()` at `javy-plugin/src/lib.rs:89`.
QuickJS resolves module dependencies while compiling, finds no loader, and the
`.unwrap()` turns the error into a panic that poisons the instance instead of
a catchable exception. Report that separately from the missing loader.

The workaround is to pre-bundle with esbuild and assign to `globalThis`. That
covers squint-compiled libraries whose only dependency is squint core.

@babashka/fs is not covered: it imports `node:fs`, `node:path`, `node:os` and
`node:zlib`. Those need Java-backed builtins. quickjs4j passes host function
arguments as JSON through jackson, so binary payloads for `zlib` and for
reading files would have to be encoded.

### Where the loader support stops

Not a technical wall. quickjs4j inherits Javy's stance that input arrives
pre-bundled, since the `javy` CLI does its own bundling.

- QuickJS: full ESM with a module loader callback.
- rquickjs 0.12, in the plugin dependency tree: `Runtime::set_loader` takes a
  custom resolver and loader.
- javy 8.0: stops here. `javy::Runtime` exposes only `context()`, with no
  accessor for the inner `rquickjs::Runtime`, and `Config` has no loader
  option.
- quickjs4j: exposes no Java-side hook.

Three ways out, cheapest first:

1. Fork the plugin and use raw FFI. `Ctx::as_raw()` gives the `JSContext`
   pointer, then `JS_GetRuntime` plus `JS_SetModuleLoaderFunc` wires a loader
   that calls back into Java over the `endive::invoke` import already carrying
   `java_invoke`. No javy change. Check first that rquickjs-sys re-exports
   `JS_SetModuleLoaderFunc`, which is unverified.
2. Get javy to expose the runtime or add a loader to `Config`, then add a
   Java-side resolver interface to quickjs4j. Cleanest, and both projects have
   to move.
3. Resolve and flatten in Java before compiling. That is writing a bundler.
   Live bindings and circular dependencies make it more than it looks.

### Reproducing

The spike adds an opt-in feature behind `BABASHKA_FEATURE_QUICKJS`, wired in
`feature-quickjs/babashka/impl/quickjs.clj`.

```
export GRAALVM_HOME=/path/to/graalvm-25
export BABASHKA_FEATURE_QUICKJS=true BABASHKA_BINARY=bb-quickjs
script/uberjar && script/compile -EBABASHKA_FEATURE_QUICKJS=true
```

`-E` is required. GraalVM 25 runs the builder in a separate process and does
not forward the environment, so without it the feature compiles out and
`bb describe` reports `:feature/quickjs false`.

```
./bb-quickjs -e '(require (quote [babashka.js :as js])) (print (js/eval-str "console.log(1+2)"))'
```

`babashka.js` exposes `eval-str`, `compile-str`, `exec-bytecode` and `runner`.
Only `console.log` output comes back. Split compile from exec when reusing a
bundle.

### Next decisions

- 9.95MB on a 69MB binary for a JS engine that cannot resolve imports.
- Option 1 is the only self-directed path, and it turns this from adding a
  dependency into maintaining a Rust to wasm artifact in the bb release
  pipeline. Weigh that cost next to the 9.95MB.
- A pod avoids both, at the cost of process startup per call.

## Session context

Design discussion happened in the choq repo sessions (2026-08-09/10).
Choq source is the reference implementation: `src/main.rs` (resolver,
loader, polyfills, url cache), `src/serve.rs` (host function
pattern), `vendor/` (pure-js builtin bundles).
