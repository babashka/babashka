# ADR 0003: babashka.ffi structs by value: named layouts, map values, libffi

## Status

Accepted 2026-08-26. Implemented on branch `ffi-structs` (PR 5 of the FFI
series). Builds on ADR 0002 (canonical descriptors) and on the libffi link
that #2051 added to every build.

## Context

C APIs pass and return small structs by value: `div_t div(int, int)`,
vectors and quaternions in physics engines, points and rectangles in UI
libraries. The pre-registered descriptor family of ADR 0002 covers scalar
signatures only. A struct by value needs the platform ABI to classify the
struct (registers, hidden pointer, homogeneous float aggregates), and a
native image cannot register new descriptors at run time. libffi does that
classification from a run-time description of the call, and since #2051
every babashka binary links it.

The question this ADR settles is the API: how a script declares a struct,
what a struct value looks like on the Clojure side, and whether the
mechanism is open for user extension. Three requirements from the
maintainer, in this order of weight when they conflict: safe, fast, and
an API that lasts for years without a breaking change.

### What other FFI libraries do

A survey of coffi, dtype-next, JNA, Java FFM, Python ctypes and cffi,
LuaJIT, Ruby FFI, koffi, OCaml ctypes, Racket, Common Lisp CFFI, Haskell,
Rust, Zig, Go, Julia, C# and Dart (full notes in the maintainer's dev
notes, `babashka.ffi/struct-survey.md`):

1. Every library that declares a layout in the host language names the
   fields. Positional forms exist only as initializer shorthands (LuaJIT
   `{1, 2}`, cffi `[1, 2]`) on top of a named layout, or where the struct
   is a native type of the language (Rust, Zig, Julia).
2. Padding is computed by the library everywhere except coffi and raw
   Java FFM, where the user writes it. coffi's manual `::padding` is a
   known source of silent garbage; the JDK at least throws on a
   misaligned member.
3. Values fall in two camps: views over memory (ctypes, cffi, LuaJIT,
   Ruby, OCaml, Racket, JNA, dtype-next, FFM) and copied host values
   (coffi maps, koffi objects, Haskell records). For a by-value call the
   ABI copies regardless.
4. By value goes through libffi (ctypes, cffi, Ruby, OCaml, Racket, CFFI,
   JNA), through a compiler or JIT (LuaJIT, Rust, Zig, Go, Julia, C#,
   Dart, FFM), or is unsupported (Haskell, dtype-next).
5. coffi is the one library with a user extension mechanism: five
   multimethods on the type keyword (`primitive-type`, `serialize*`,
   `deserialize*`, `c-layout`, `serialize-into`, `deserialize-from`).
   coffi implements its own structs, arrays and strings through them. Of
   177 files on GitHub that use coffi's `defcfn`, four repositories extend
   them, and every case is the same: a C struct mapped to a host type
   (fastmath `vec3`, a `Quaternion` record, a matrix).

### The proof of concept

The POC branch (`ffi-port-validation`) implemented positional layouts,
`{:struct [:int :int]}`, with vector values `[3 1]`, computed padding, a
cross-check of the computed layout against libffi's own at bind time,
per-thread scratch memory, and a libffi call path (`ffi_prep_cif`,
`ffi_call` through `@CFunction` bindings in the image, the system libffi
through FFM on the JVM). Measured cost of a struct call: about 1us
against 120-150ns for a scalar call through a trampoline.

## Decision

### Layouts are data, named, with computed and verified padding

```clojure
(def point {:struct [[:x :int] [:y :int]]})
(def rect  {:struct [[:lo point] [:hi point]]})

(ffi/sizeof point)   ;;=> 8
(ffi/alignof rect)   ;;=> 4
```

- A layout is a map with a `:struct` key whose value is a vector of
  `[name type]` pairs. A type is a type keyword or another layout, so
  layouts nest by value. Names are keywords.
- Padding and alignment follow the C rules of the platform, computed by
  babashka. At bind time the computed size and alignment are compared
  with what libffi computes for the same `ffi_type`; a difference is an
  error, never a silent misread.
- The map form is open: `:packed`, `:align`, `{:array type n}` and
  `{:union ...}` can be added without touching what exists.
- `sizeof` and `alignof` accept layouts as well as type keywords.

### Values are maps, one form

```clojure
(defcfn p2-add "p2_add" [point point] point)
(p2-add {:x 1 :y 2} {:x 10 :y 20})   ;;=> {:x 11 :y 22}
```

- A struct value is a map from field name to value; a nested struct is a
  nested map. This is what every named-layout library returns.
- One form only. No positional vector as a shorthand: a second input form
  is a second code path to keep correct forever, and the survey shows it
  exists elsewhere only as sugar on top of names.
- A missing field is an error at the call, not a zero written to memory.
  An unknown field is an error too: a typo in a field name must not
  vanish silently.
- Values are copied, not memory-backed views. For a by-value call the ABI
  copies anyway, so a view buys nothing here. Views over memory belong to
  the later `read`/`write`-with-layout step, which the same layouts
  serve.

### No user extension mechanism, for now

The maintainer's three requirements all point the same way:

- Fast: coffi dispatches a multimethod per field per call. In babashka
  those methods would be interpreted by sci, roughly 100ns per field on
  top of the call. A wrapper function costs one sci call, once:
  `(defn body-position [id] (let [{:keys [x y z]} (c-body-position id)]
  (vec3 x y z)))`. That is what the four real users of coffi's hooks do,
  in more lines.
- Safe: five multimethods with their own argument conventions are where
  silent garbage starts (a wrong offset, forgotten padding). Layouts as
  data can be validated; user code in the marshalling path cannot.
- Stable: it is API surface that would have to hold for years. The door
  stays open and additive: a layout could later carry conversion hooks,
  `{:struct [...] :as {:from ->vec3 :to vec3->map}}`, called once per
  value with no dispatch, if demand appears.

coffi needs the hooks because `defcfn` does the marshalling inside the
generated function. babashka's `defcfn` has the same shape, and wrapping
the generated function is as short as a `defmethod`.

### Mechanism

- Only a signature that contains a struct goes through libffi; every
  other signature stays on the trampolines of ADR 0002.
- At bind time everything is computed once: field offsets, one
  specialized reader and writer per field, the `ffi_type` tree and the
  call interface (cif). A call loops over the fields into per-thread
  scratch memory and calls `ffi_call`. No type dispatch per call.
- A variadic signature cannot carry a struct by value: libffi's variadic
  interface and the platform ABIs disagree on it, and the descriptor
  family does not cover it.
- In a binary without libffi (`BABASHKA_LIBFFI=none`, the musl static
  build) a struct binding throws at bind time with a message that points
  at `bb describe` and its `:libffi/version`. On the JVM the system
  libffi is used through FFM; without one, the same error.
- The bound function carries `:babashka.ffi/backend :libffi` in its
  metadata, so a test or a user can see which path a binding took.

## Consequences

- The POC's positional layouts and vector values change to named layouts
  and maps before anything is released, so no user sees the change.
- Per call, maps cost about 10-20ns more than vectors (one array-map
  allocation on return, a linear key scan per field on input), which is
  1-2% of a libffi call. A struct with more than eight fields becomes a
  hash-map, still under 10%.
- The same layouts serve the later additions: `(alloc arena point)`,
  `(read p point)` returning a map, `(write p point m)`, arrays, unions,
  conversion hooks. None of those needs a change to what this ADR
  defines.
- Struct-by-value in callbacks (libffi closures) is out of scope here and
  stays on the roadmap with the libffi fallback for uncovered signatures
  (PR 6).
