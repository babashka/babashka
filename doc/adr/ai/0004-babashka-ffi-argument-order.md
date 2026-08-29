# ADR 0004: Argument order in babashka.ffi

## Status

Accepted 2026-08-28 after a review of each public function. The
`ffi-arg-order-adr` branch implements the decision. The changes are listed at
the end.

## Context

The API is positional, and several functions have optional arguments, so the
same position can hold different things in different arities. `alloc` took an
arena before the size, `read` takes an offset after the type, and `cfn` takes a
library before the symbol.

Without a written rule, reviewers cannot assess a new function. A guessed
argument order does not always produce an error.

This decision states the rules, applies them to every public function, and
records what changes.

## Decision

Three rules. Rule A is about safety and outranks the others. Rule B decides
placement. Rule C covers the case Rule A cannot reach.

### Rule A: two arguments that can hold the same kind of value never trade places

If position three holds a number in one arity, no other arity can put a
different number there.

This is the only rule that can require a change to an existing function. The
result depends on an incorrect call, not on preference. A
position can hold different things across arities when the types differ,
because then a mistake throws:

```clojure
(ffi/alloc 8 arena)
;; babashka.ffi: alloc takes an integer byte count or a type keyword,
;; got #object[jdk.internal.foreign.ArenaImpl ...]

(ffi/write p :int64 3 7)
;; no exception. Writes 7 at offset 3, where the caller meant 3 at offset 7.
```

Bounds checks catch an offset outside the segment, so a mistake on a small
allocation does throw. It stays silent when both numbers are valid offsets,
which is exactly the array and struct case the offset arity exists for.

### Rule B: an optional argument goes last, unless it is the source the result comes from

The default is to append. The leading position is reserved for the argument
that says where the next value comes from, replacing a default source:

```clojure
(alloc arena n)                ; the arena provides the memory
(cfn lib sym argtypes rettype) ; the library provides the symbol
(find-symbol lib sym)

(reinterpret seg size arena)   ; the arena provides nothing here: the segment
                               ; already exists and only gains a lifetime
```

Providing is narrower than owning, and that is what separates `alloc` from
`reinterpret`.

Clojure core uses the same rule. Its lookup functions reserve the leading
position for a scope and append ordinary optional arguments elsewhere:

```
resolve      ([sym] [env sym])
ns-resolve   ([ns sym] [ns env sym])
get          ([map key] [map key not-found])
subs         ([s start] [s start end])
```

`resolve` has the shape of `find-symbol` exactly.

### Rule C: keep the types of adjacent parameters disjoint

When two neighboring parameters can be swapped, keep their accepted types
separate. Then at least one direction of the swap throws. Rule A forbids a
position from changing meaning between arities. Rule C covers two parameters
of one type that are adjacent in a single arity.

The worked example is `alloc`. Its `n` is polymorphic and takes a byte count, a
type keyword or a layout, while its `alignment` takes an integer only:

```clojure
(ffi/alloc arena :pointer 64)  ; a pointer-sized block on a 64-byte boundary
(ffi/alloc arena 64 :pointer)  ; babashka.ffi: alloc takes an integer alignment
```

If alignment also accepts a type, both calls are valid and mean different
things. This creates the hazard instead of catching it. An
alignment that does come from a type is already available as `(alignof t)`,
which returns an integer and composes.

Rule C applies only when one of the two parameters carries a type. Two integers
side by side cannot be told apart. Examples are the address and size of
`segment`, the offset and length of `slice`, and the count and offset of
`read-bytes`. That residual risk is inherent to a positional API. Document the
risk instead of designing around it.

### One exemption: the def-form prefix

`defcfn` appears to break Rule A. Position two holds the C symbol when there
is no docstring and the docstring when there is one, and both are strings.

It is exempt, because `name docstring? attr-map?` is the convention of every
def form in Clojure. Placing the docstring elsewhere causes more mistakes than
the shift prevents.

The ambiguity is resolved structurally rather than by type. The parser anchors
on the first literal vector that is not a layout. It takes the C symbol as the
previous element and treats everything earlier as the prefix. With dynamic
argtypes there is no anchor, so the parser counts back three from the end. All
four legal shapes parse correctly. Without a C symbol, the parser uses the
docstring as the symbol:

```clojure
(defcfn e "Doc." at :size_t)   ; C symbol omitted
;; babashka.ffi: symbol not found: Doc.
```

The exemption covers that prefix and nothing else. It is not a general license
for same-type shifts.

## The audit

The audit covered every public var on 2026-08-28.

| Function | Arities after this decision | Verdict |
| --- | --- | --- |
| `segment` | `[addr]` `[addr size]` | unchanged |
| `reinterpret` | `[seg size]` `[seg size arena]` `[seg size arena cleanup]` | unchanged, new docstring |
| `slice` | `[seg offset]` `[seg offset len]` | unchanged, `len` also takes a layout |
| `find-symbol` | `[sym]` `[lib sym]` | unchanged |
| `cfn` | `[sym argtypes rettype]` `[lib sym argtypes rettype]` | unchanged |
| `defcfn` | `[name docstring? attr-map? sym argtypes rettype ...]` | exempt |
| `alloc` | `[arena n]` `[arena n alignment]` | the one-argument form is removed |
| `free` | `[p]` | unchanged, new docstring |
| `read` | `[p t]` `[p t offset]` | unchanged |
| `write` | `[p t v]` `[p t v offset]` | the offset moves to the end |
| `read-bytes` | `[p n]` `[p n offset]` | unchanged |
| `write-bytes` | `[p arr]` `[p arr offset]` | unchanged |
| `string->ptr` | `[arena s]` | takes an arena |
| `byte-buffer`, `sizeof`, `alignof`, `address`, `size`, `pointer?`, `ptr->string`, `null?`, `callback`, `free-callback`, `load-library`, `load-system-library`, the four arena constructors | one arity each | nothing to order |

After the change to `write`, the whole access family has one shape:

```clojure
(read        p t   offset?)
(write       p t v offset?)
(read-bytes  p n   offset?)
(write-bytes p arr offset?)
```

The pointer comes first, then what is read or written, and the offset is last
and optional, with no exceptions left.

## What changes, and why

### `alloc` requires an arena

The one-argument form that took memory from the C allocator is removed. No
`malloc` replaces it. `free` stays, for memory that a C function returned.

This is not an ordering decision. The order followed from the rules, and the
question was how many ownership models the API offers. It now offers one,
because the arena is the model the platform has chosen.

The FFM API has no malloc and no free. `Arena` exposes `allocate`, `scope` and
`close`, and manual lifetime is a confined or shared arena that the caller
closes. Neither word appears in coffi's source either.

The four libraries barely used the other model. ffi-sqlite3 and the sqlite4clj
port are already arena-only with no `free` at all. ffi-brotli holds eighteen
manual allocations in three patterns, all lexically scoped. One arena per
function replaces them and deletes eight `free` calls and a `run!`. ffi-duckdb
has one non-lexical allocation, `pdb`. It lives from `open` until `close!` and
becomes a shared arena in the connection map.

Across all four libraries, no `ffi/free` call releases memory that a C function
returned. Each call releases memory from `alloc`. ffi-duckdb uses
`c-duckdb-free` for foreign memory in `varchar-at`. Thus, `(alloc n)` and `free`
formed a closed loop.

That loop also produced a bug found the same day. `free` decides what to accept
by comparing scopes. Memory from the global arena has the same scope as C
memory, so the guard cannot tell them apart:

```clojure
(ffi/free (ffi/alloc (ffi/global-arena) 64))
;; accepted, and calls C free() on memory C never allocated
```

Removing unscoped `alloc` removes this call pattern. The guard still catches
confined and shared arena memory, which is the common error. Global arena memory
remains indistinguishable. The docstring records this limitation.

`string->ptr` follows in the same decision, because it also handed out C memory
that needed `free`. It becomes `(string->ptr arena s)`, the arena leading under
Rule B, mapping directly onto `SegmentAllocator.allocateFrom(String)`, which the
existing argument coercion already uses.

Updating babashka changes 42 `alloc` and 25 `free` calls in the test suite.
Some calls exist only to exercise the C allocator path. The update also changes
two sections of `doc/ffi.md` that teach both models.

### `write` moves its offset to the end

```clojure
;; before
(write p t v)
(write p t offset v)

;; after
(write p t v)
(write p t v offset)
```

The old order breaks Rule A because position three holds a value in one arity
and an offset in the other. Both are numbers. It breaks Rule B because the
offset is optional, provides nothing, and belongs at the end.

Every other function that takes an offset puts it last. A caller who knows
`(write p t v)` adds an offset as `(write p t v offset)`. The rest of the API
teaches this order. Today, that call silently means something different.

The fix relocates part of the risk rather than removing it. In the new order,
`v` and `offset` sit next to each other and are both often numbers, so Rule C's
limit applies. This is still an improvement on two counts. A transposition now
affects only the four-argument form. This is the same irreducible risk that
`segment` and `slice` carry. When `t` is a layout, `v` is a map, so a
transposition throws.

Migration is small. Nearly every call site uses the three-argument form. The
four-argument ones are babashka's own tests plus `(ffi/write pconn :pointer 0
conn)` in ffi-duckdb, where the zero is redundant anyway.

### `alloc` and `slice` accept a layout, and `layout-of` is memoized

`alloc` takes a layout where it takes a type keyword, and `slice` takes one
where it takes a length:

```clojure
(alloc arena [:struct [[:x :int] [:y :int]]])
(slice arr (* i (sizeof point)) point)
```

`sizeof` and `alignof` already accept a layout while `alloc` and `slice` refuse
one, so the caller writes `(alloc arena (sizeof point) (alignof point))`. That
walks the layout tree twice, and writing an alignment by hand is the step this
removes. It also strengthens Rule C, since `offset` and `alignment` stay
integers while their neighbors accept a layout.

Measured cost per call:

```
(sizeof :pointer)   keyword           48 ns
(sizeof point)      flat struct      554 ns
(sizeof rect)       nested struct   1565 ns
sizeof + alignof point              1133 ns   <- what callers write today
(alloc arena 8)     baseline         120 ns
```

The new form is about twice as fast as the workaround because it resolves the
layout once. Layout resolution still costs four to thirteen times the
allocation. `layout-of` validates the structure, visits each field, and builds
a new map on every call.

`layout-of` uses a bounded cache, in the style of the tail-shape cache. A layout
is immutable, so a cached value cannot become stale. The cache keeps the first
256 layouts. Further layouts resolve without entering the cache. Thus,
generated layouts cannot evict the working set. Hashing costs less than layout
resolution:

```
hash of a hoisted layout             34 ns
hash of a fresh literal              33 ns
map lookup, hoisted key              42 ns
map lookup, fresh literal key       102 ns
```

Even the worst case is five times cheaper than recomputing.

The implementation does not build `java.lang.foreign.MemoryLayout` objects,
although the JVM and coffi use them. A `MemoryLayout` computes its size and
alignment once and stores them, so holding the object is the cache. It helps
coffi more because coffi passes structs through FFM downcalls. Its layout object
feeds the function descriptor directly. Babashka structs go through libffi and
need `ffi_type` descriptors. For babashka, a `StructLayout` serves size and
alignment only. It also adds a second layout representation to document.

coffi does not memoize. `size-of` and `align-of` rebuild the layout unless the
caller passes a `MemoryLayout` instance. `defalias`, where caching naturally
occurs, delegates at call time:

```clojure
(defmethod c-layout ~new-type [_type#] (c-layout aliased#))
```

### `reinterpret` keeps its arguments and gets a new docstring

The arena stays optional because the two forms make different and equally valid
claims about ownership. Without an arena you get a window onto memory that C
owns and that outlives your code. With an arena you bind the foreign pointer's
validity to that arena, and the runtime then enforces it:

```clojure
(def view (with-open [a (ffi/confined-arena)] (ffi/reinterpret p 6 a)))
(ffi/ptr->string view)
;; babashka.ffi: the pointer at address 105553138057360 belongs to a closed arena
```

The runtime reports this use-after-free as an exception. Requiring an arena
forces the caller to name one when it controls nothing. The code then states a
false ownership claim without a safety gain.

The old docstring only described the arena form. The new text also describes
the form without an arena:

```
Returns a view of segment seg with byte size size.

Without an arena the view has an unbounded lifetime. That is correct for
memory that C owns and that outlives your code.

With an arena, the view is valid only while that arena is open. A read after
the arena closes throws. The arena calls the optional cleanup function with
the view when it closes. Use this function for a C library deallocator.

CAUTION: Give the actual size. The runtime cannot know if this size is
correct. A larger size permits out-of-bounds reads.

CAUTION: If the arena is closed, do not pass the view to C. C can access the
released memory.
```

## Alternatives considered

### Put the arena last, as coffi does

coffi is consistent: `(alloc size arena)`, `(alloc-instance type arena)`,
`(serialize obj type arena)`. Matching it helps people port code.

This does not apply to `alloc`, which requires an arena and has no optional
leading argument. It still applies to `cfn` and `find-symbol`, and there it is
rejected. Appending gives `(cfn sym argtypes rettype lib)`, which puts the
library three positions from the symbol it qualifies, and `clojure.core/resolve`
already prepends the scope in exactly this shape.

### Keep the Java order in `write` and drop the convenience arity

`(write p t offset v)` as the only form. Nothing is optional, so no position
shifts, Rule A holds, and the call matches `MemorySegment.set` exactly. This is
the most faithful answer to the FFM API.

Rejected because almost every call site writes at offset zero. This form makes
each of them carry a literal `0` that says nothing.

### Mirror the Java method, with the receiver first

This is the rule the code followed without saying so. `Arena.allocate` gives
`(alloc arena n)`, and `MemorySegment.reinterpret(size, arena, cleanup)` gives
`(reinterpret seg size arena cleanup)`.

Rejected as the stated rule because it is invisible. A caller who does not know
the Java API cannot derive it. It also gives no answer for a function that wraps
no single Java method. Rule B reaches the same outcome without requiring Java
API knowledge.

### Add a `malloc` beside an arena-only `alloc`

Splitting by name also splits by ownership model, which is the relevant
distinction. JNA does this with `Native.malloc` and `Native.free` beside
its garbage-collected `Memory`.

Rejected because FFM arenas replace this split. Libraries built on FFM dropped
it. The rare case where C takes ownership of memory allocated by babashka
requires one `cfn` line at the call site.

## How this compares to the FFM API

Every rule agrees with FFM except the change to `write`. `Arena.allocate(size,
alignment)` puts the arena in the receiver position, which is `(alloc arena n
alignment)`. `MemorySegment.reinterpret(size, arena, cleanup)` keeps the
segment as the receiver. `MemorySegment.get(layout, offset)` is `(read p t
offset)`.

Then `MemorySegment.set(layout, offset, value)` puts the offset before the
value, and the new `write` order does not. Java can use that order because `set`
has no arity that omits the offset, so no position in it ever changes meaning.
The hazard is not the order but a convenience arity combined with that order,
and the convenience arity is ours.

coffi made the same two original choices:

```clojure
(defn read-long  ([segment] ...) ([segment offset] ...))
(defn write-long ([segment value] ...) ([segment offset value] ...))
```

Its reads append the offset and are safe. Its writes insert it, so position two
holds a value in one arity and an offset in the other, both longs.
`write-longs` repeats the shape. Thus, fixing `write` moves babashka away from
coffi, but not from a documented coffi decision. Both libraries mirrored
`MemorySegment.set` and inherited a hazard that the Java method does not have.

## Code changes this decision calls for

- `alloc`: remove the one-argument form, and accept a layout wherever it
  accepts a type keyword.
- `write`: `[p t v]` and `[p t v offset]`.
- `slice`: accept a layout as the length.
- `string->ptr`: becomes `[arena s]`.
- `layout-of`: memoize with a bounded cache.
- `reinterpret`: replace the docstring with the text above.
- `free`: rewrite the docstring around memory a C function returned. State that
  the function cannot distinguish a global arena pointer from a C allocator
  pointer.
- `segment`: document that an address and a size are both numbers, so a
  transposed call builds a valid-looking pointer that fails later.
- `cfn`: name the cause when a layout vector appears on an argtypes position.
  Today that reports `unknown type :struct`, which describes the symptom.
- ffi-duckdb: `pdb` becomes a shared arena in the connection map, and the
  redundant zero in `(ffi/write pconn :pointer 0 conn)` goes.
- ffi-brotli: the segment sweep also replaces its eighteen manual allocations
  with one arena per function.
- babashka: rework the tests that exercise the C allocator path, and the two
  sections of `doc/ffi.md` that teach both ownership models.

## Found during the review, tracked elsewhere

These topics do not concern ordering and remain on the project list:

- Read and write structs through a layout.
- Let `ptr->string` read a zero-size segment. `string-at` already reads
  unbounded behind each `:string` return. This behavior requires the
  `Long/MAX_VALUE` call in ffi-duckdb.
- Use the cleanup argument of `reinterpret` to attach a C deallocator.
- Let `write-bytes` write part of an array.

## Sequencing

The source will move to the babashka/ffi repository. These changes break the
four-argument `write` and the one-argument `alloc`. They land before the
library's first release, and the changelog records them.

## Amendment 2026-08-29: `callback` takes an arena

`callback` moved from `[f argtypes rettype]` to `[arena f argtypes rettype]`,
and `free-callback` is gone. The table above lists both under "nothing to
order"; that row is a snapshot of the API as it stood when this decision was
accepted.

The new order follows the rules this ADR states rather than departing from
them. Rule B puts an optional argument last unless it is the source the result
comes from. The arena is that source: it owns the returned function pointer
exactly as it owns the memory `alloc` returns, so it leads, and the three
lifetime-bearing functions now read the same way.

```clojure
(ffi/alloc arena :int64)
(ffi/string->ptr arena "hello")
(ffi/callback arena f [:pointer] :void)
```

An earlier sketch in the project notes put the arena last and optional, to
match coffi. That sketch predates this ADR. Mandatory-and-first is what lets
`free-callback` go: closing the arena releases the stub, a global arena keeps
it for the life of the process, and an automatic arena releases it once the
pointer becomes unreachable, which the old API could not express.

Rules A and C still hold. There are no arities to trade places, and each pair
of adjacent parameters has disjoint types: an arena, then a function, then a
vector of argument types, then a return type keyword. A callback return cannot
be a layout, so the last two never collide.

## Amendment 2026-08-29: `free` is removed

The table lists `free` as `[p]`, "unchanged, new docstring", and the prose
says it stays for memory that a C function returned. It does not stay.

`free` called the C allocator's `free`. That is correct only for a library
that allocated with `malloc`. A library that ships its own deallocator
allocates from its own heap, and on Windows a library built against another C
runtime has a different heap, so the wrong deallocator corrupts it instead of
failing.

Its guard could not be made to hold either. It refused arena memory by
comparing scopes, but FFM gives a global arena segment the same scope object
as a raw C pointer, so global arena memory passed the check and reached the C
allocator. The docstring asked the caller to keep a distinction that nothing
in the API shows.

`reinterpret` already covers the case, and orders its arguments by the same
rules: the pointer is the subject, and the arena and the deallocator follow
it.

```clojure
(with-open [arena (ffi/confined-arena)]
  (ffi/ptr->string (ffi/reinterpret p 64 arena duckdb-free)))
```

With this and the `callback` amendment above, an arena owns every lifetime in
the API. No function releases anything by itself.

## Amendment 2026-08-29: `read-array` and `write-array` replace the byte pair

`read-bytes` and `write-bytes` are gone. `(read-array p t n offset?)` and
`(write-array p t arr offset?)` copy elements of any scalar type between
native memory and the Java array of that width, as one memcpy; `:byte` is
the case the old pair covered. The order follows the rules above: the
pointer is the subject, the type comes next as in `read` and `write`, and
the optional offset is last. Two integers stay adjacent at the end, the
same shape `read-bytes` had, which this ADR listed as unchanged.
