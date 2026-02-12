# ADR 0005: Supporting Map Interfaces in deftype via SciMap

## Status

Accepted

## Context

Babashka's SCI interpreter only supports protocol implementations in
`deftype`/`defrecord`, not JVM interface implementations. This prevents
libraries like `clojure.core.cache` from working in babashka, because their
`deftype` forms implement map interfaces such as `ILookup`, `IPersistentMap`,
`Associative`, `Counted`, `Seqable`, `IPersistentCollection`, and `Iterable`.

For example, core.cache's `defcache` macro expands to a `deftype` with all seven
of those interfaces to make cache instances behave as persistent maps — so you
can use `get`, `assoc`, `dissoc`, `seq`, keyword access, etc. directly on a
cache value.

The challenge is threefold:

1. **SCI cannot generate bytecode** — it interprets code, so it cannot create
   new JVM classes at runtime the way `deftype` normally does.

2. **A partial match is unsound** — if we use a pre-built Java class that
   extends `APersistentMap`, it always implements *all* map interfaces.
   Accepting a deftype that only declares `ILookup` would still make
   `(instance? clojure.lang.Associative x)` return `true`, which is incorrect
   and would cause subtle bugs in code that relies on interface checks.

3. **Different libraries use different interface sets** — we cannot assume one
   fixed set covers all cases.

## Decision

Introduce a pre-built Java class `babashka.impl.SciMap` that extends
`APersistentMap` and implements `IObj`, `IKVReduce`, `IMapIterable`,
`Reversible`, and `ICustomType`. It delegates all method calls to a Clojure
function map, and stores deftype field values for SCI's `.fieldName` access.
SCI gains a new pluggable `:deftype-fn` option (alongside the existing
`:reify-fn` and `:proxy-fn`), keeping SCI decoupled from babashka-specific code.

### Architecture

```
SCI (generic)                         Babashka (specific)
─────────────                         ──────────────────
deftype macro                         babashka.impl.deftype/deftype-fn
  │                                     │
  ├─ resolves interfaces                ├─ checks interface combo
  ├─ calls :deftype-fn if present       ├─ if match: returns constructor symbol
  ├─ compiles methods into fn forms     ├─ if no match: returns nil
  ├─ calls constructor with map         └─ (falls through to SCI error)
  ├─ if nil result: standard path
  └─ (errors on unknown interfaces)   babashka.impl.deftype/->scimap
                                        constructor fn mapped in SCI ctx
                                        receives {:methods :fields :protocols}

                                      babashka.impl.SciMap (Java)
                                        extends APersistentMap
                                        implements IObj, IKVReduce,
                                          IMapIterable, Reversible, ICustomType
                                        delegates methods via fn map
                                        stores fields via ICustomType.getFields()
```

### The :deftype-fn API

The `:deftype-fn` option is a function called at macro-expansion time when a
`deftype` contains JVM interfaces. It receives a map with:

- `:interfaces` — set of resolved Java classes declared by the deftype

It returns either:

- A **fully qualified symbol** naming a constructor function mapped in the SCI
  context (via `:namespaces`), or
- `nil` to fall through to the standard SciType path

When a symbol is returned, SCI handles all boilerplate:

1. Compiles all protocol/interface method bodies into `(fn [args] body)` forms,
   grouping same-named methods into multi-arity fns
2. Generates code that calls the constructor symbol with a map:
   `(ctor-sym {:methods {'valAt (fn ...) ...} :fields {'cache cache} :protocols #{...}})`
3. Wraps it in the standard `declare`/`def`/`defn` boilerplate for type
   registration and factory function

The constructor function receives this map and creates the instance. For
babashka, `babashka.impl.deftype/->scimap` creates a `SciMap`:

```clojure
(defn ->scimap [{:keys [methods fields protocols]}]
  (SciMap. methods fields nil protocols nil))
```

This design keeps SCI decoupled from any specific Java class — it only knows
about symbols and maps. The constructor function is a regular Clojure function
registered in the SCI namespace config.

### Interface Matching

The matching has two parts:

1. **Required interface** — `IPersistentMap` must be declared. This is the
   key signal that the user wants a full map type. Without it, deftype-fn
   returns nil and falls through to the standard SciType path.

2. **Inherent interfaces are allowed** — `APersistentMap` (plus SciMap's
   extra interfaces) already implements many interfaces. These are computed
   dynamically via `(supers APersistentMap)` plus `IMeta`, `IObj`,
   `IKVReduce`, `IMapIterable`, and `Reversible`. If a deftype declares
   any of these, they are silently accepted. If it omits them, they are
   still present on the resulting object — but this is acceptable (not a
   false positive) because SciMap provides real working implementations
   for all of them. This matches the behavior of `defrecord` in standard
   Clojure, which also inherits interfaces from `APersistentMap` regardless
   of what the user declares.

   The full inherent set includes: `ILookup`, `IPersistentMap`,
   `Associative`, `IPersistentCollection`, `Counted`, `Seqable`,
   `Iterable`, `IHashEq`, `MapEquivalence`, `java.util.Map`, `IFn`,
   `Callable`, `Runnable`, `Serializable`, `IMeta`, `IObj`, `IKVReduce`,
   `IMapIterable`, `Reversible`.

   This means libraries can declare any subset of these — all 7 core map
   interfaces (core.cache), 6 of 7 + IMeta/IObj (clerk), or the full set
   plus Reversible (linked) — and they all match.

3. **Novel interfaces are rejected** — if the deftype declares an
   interface that is not inherent (e.g. `Sorted`), deftype-fn returns
   nil and falls through to SCI's standard error.

### How SciMap Works

- **Method dispatch**: The constructor receives an `IPersistentMap` of
  `Symbol → IFn`. Java methods like `valAt`, `assoc`, `seq` look up their
  implementation by symbol and invoke it, passing `this` as the first
  argument (matching Clojure's deftype convention).

- **Symbol normalization**: The constructor strips namespaces from symbol
  keys, because macros using syntax-quote produce qualified names (e.g.
  `clojure.core.cache/valAt` instead of `valAt`).

- **Field access**: Field values are stored in a separate `IPersistentMap`
  exposed via `ICustomType.getFields()`. SCI's evaluator uses this interface
  for `.fieldName` access on deftype instances.

- **Protocol dispatch**: SciMap implements `ICustomType`, so SCI's protocol
  method dispatch finds implementations via `getMethods()`. Protocol
  satisfaction is tracked via `getProtocols()`.

- **Required methods** (abstract from APersistentMap — must be in methods
  map): `valAt`, `assoc`, `without`, `containsKey`, `entryAt`, `count`,
  `empty`, `seq`, `iterator`.

- **Optional methods** (delegate to user impl if provided, fall back to
  default): `cons`, `equiv`, `toString`, `hasheq`, `size`, `meta`,
  `withMeta`, `kvreduce`, `keyIterator`, `valIterator`, `rseq`.

  Defaults:
  - `cons`, `equiv`, `hasheq`, `size` → `super` (APersistentMap)
  - `meta` → returns `_meta` field
  - `withMeta` → creates new SciMap with updated `_meta`
  - `kvreduce` → iterates `seq()`, applies f to each entry
  - `keyIterator`/`valIterator` → iterates `seq()`, extracts key/val
  - `rseq` → reverses `seq()` into a cons list

- **Reflection config**: SciMap requires a GraalVM native-image reflection
  entry with `allPublicMethods` because SCI resolves untyped Java interop
  calls (e.g. `(.hasheq x)` without a type hint) reflectively at runtime.

## Libraries Enabled

Libraries that use `deftype` with map interfaces and can now work
unmodified in babashka:

- **clojure.core.cache** — the primary motivating library. All cache types
  (BasicCache, FIFOCache, LRUCache, TTLCacheExpiry, LUCache) work with the
  original unmodified `.clj` file. Declares all 7 core map interfaces.

- **clojure.core.cache/wrapped** — atom-based cache wrappers built on
  core.cache.

- **nextjournal/clerk** — `AlwaysArrayMap` declares 6 of the 7 core
  interfaces (omitting `Counted`) + `IMeta` + `IObj`. Works because
  `Counted` is inherent and `IPersistentMap` is present.

- **frankiesardo/linked** — `LinkedMap` declares 13 interfaces including
  `Reversible`, `MapEquivalence`, `java.util.Map`, `IFn`, `IHashEq`.
  All are inherent. Provides custom `rseq` for reverse insertion-order
  traversal, which SciMap delegates to.

Libraries that use the `defcache` macro from core.cache (inheriting the
same interface set):

- **asami** — contains a fork of `defcache` with the identical interface set.
- **datahike** — `LRUDatomCache` uses `defcache` from core.cache.

Libraries that add `IHashEq`, `MapEquivalence`, `java.util.Map`, `IFn`,
`IKVReduce`, `IMapIterable`, or `Reversible` on top of the core set also
match, since these are all inherent to SciMap.

## Consequences

### Positive

- `clojure.core.cache` works in babashka with zero modifications to the
  library source
- Libraries with varying interface subsets all work — from 6 interfaces
  (clerk) to 13 interfaces (linked) — because the matching only requires
  `IPersistentMap` and rejects only novel interfaces
- The `:deftype-fn` hook is generic — other interface combos can be added
  later by defining new pre-built classes and extending the combo set
- SCI remains decoupled from babashka-specific Java classes — deftype-fn
  returns a symbol, SCI only deals with symbols and maps
- Method compilation happens in SCI's common path, so all deftype-fn
  implementations get consistent method handling for free
- Inherent interfaces (`IHashEq`, `IFn`, `IKVReduce`, `IMapIterable`,
  `Reversible`, etc.) are freely accepted, matching the behavior of
  `defrecord` in standard Clojure
- `reduce-kv`, `keys`, `vals`, and `rseq` all work with sensible
  seq-based defaults, with optional custom delegation

### Negative

- Libraries needing novel interfaces not inherent to SciMap (e.g. `Sorted`)
  get an error listing the unsupported interfaces
- SciMap normalizes symbol keys in the constructor (O(n) per instantiation)
- `(instance? Reversible x)` returns true for all SciMap instances even
  if the deftype didn't declare `Reversible` — the default `rseq`
  reverses `seq()` which is always correct but may be unexpected for
  unordered maps. In practice this is harmless: no code checks
  `reversible?` on cache instances, and the worst case is O(n) `rseq`
  where O(1) was expected

### Files Changed

**New:**
- `babashka/impl-java/src-java/babashka/impl/SciMap.java` — pre-built map class
- `babashka/src/babashka/impl/deftype.clj` — deftype-fn with combo matching
  and `->scimap` constructor function

**Modified:**
- `babashka/sci/src/sci/impl/deftype.cljc` — pluggable `:deftype-fn` hook
  with method compilation in common path
- `babashka/sci/src/sci/impl/opts.cljc` — wire `:deftype-fn` option
- `babashka/src/babashka/main.clj` — pass `deftype-fn` to SCI init, map
  `->scimap` in SCI namespace config
- `babashka/src/babashka/impl/classes.clj` — register SciMap with
  `allPublicMethods` for untyped interop. Add reflection entries for
  interfaces used by deftype method bodies: `IMeta` (`meta`),
  `IObj` (`withMeta`), `IPersistentMap` (`allPublicMethods`),
  `ILookup` (`valAt`), `IHashEq` (`hasheq`), `IKVReduce` (`kvreduce`),
  `IMapIterable` (`keyIterator`/`valIterator`), `Reversible` (`rseq`),
  `MapEquivalence`, `java.util.Map$Entry` (`getKey`/`getValue`),
  `SeqIterator` (`allPublicConstructors`), and add `iterator` method to
  `PersistentHashMap`/`PersistentArrayMap` for native image

**Lib tests added:**
- `test-resources/lib_tests/clojure/core/cache.bb` — bb-compatible source shadow
- `test-resources/lib_tests/clojure/core/cache_test.bb` — bb-compatible test shadow
- `test-resources/lib_tests/linked/core.bb` — map-only shadow (set stub)
- `test-resources/lib_tests/linked/set.bb` — stub ns
- `test-resources/lib_tests/linked/map_test.bb` — bb-compatible map tests
