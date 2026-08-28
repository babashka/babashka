# ADR 0004: Argument order in babashka.ffi

## Status

Accepted 2026-08-28, after reviewing every public function one at a time, and
implemented on branch `ffi-arg-order-adr`. The changes are listed at the end.

## Context

The API is positional, and several functions have optional arguments, so the
same position can hold different things in different arities. `alloc` took an
arena before the size, `read` takes an offset after the type, and `cfn` takes a
library before the symbol.

Without a written rule each new function is a coin flip and a reviewer has
nothing to check against. Worse, a caller who guesses the order wrong does not
always get an error.

This decision states the rules, applies them to every public function, and
records what changes.

## Decision

Three rules. Rule A is about safety and outranks the others. Rule B decides
placement. Rule C covers the case Rule A cannot reach.

### Rule A: two arguments that can hold the same kind of value never trade places

If position three holds a number in one arity, no other arity may put a
different number there.

This is the only rule that can condemn a function that already exists, and it
is decided by what happens to a caller who guesses wrong, not by taste. A
position may hold different things across arities when the types differ,
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

This is not our invention. Clojure core already reserves the leading position
for a scope in its lookup functions and appends ordinary optional arguments
elsewhere:

```
resolve      ([sym] [env sym])
ns-resolve   ([ns sym] [ns env sym])
get          ([map key] [map key not-found])
subs         ([s start] [s start end])
```

`resolve` has the shape of `find-symbol` exactly.

### Rule C: keep the types of adjacent parameters disjoint

When two neighbouring parameters could be swapped, keep the sets of types they
accept apart, so that at least one direction of the swap throws. Rule A forbids
a position from changing meaning between arities; Rule C covers two parameters
of one type standing next to each other inside a single arity.

The worked example is `alloc`. Its `n` is polymorphic and takes a byte count, a
type keyword or a layout, while its `alignment` takes an integer only:

```clojure
(ffi/alloc arena :pointer 64)  ; a pointer-sized block on a 64-byte boundary
(ffi/alloc arena 64 :pointer)  ; babashka.ffi: alloc takes an integer alignment
```

Letting alignment take a type as well would make both calls valid and mean
different things, which manufactures the hazard instead of catching it. An
alignment that does come from a type is already available as `(alignof t)`,
which returns an integer and composes.

Rule C only bites when one of the two parameters carries a type. Two integers
side by side cannot be told apart, as in the address and size of `segment`, the
offset and length of `slice`, or the count and offset of `read-bytes`. That
residual risk is inherent to a positional API and belongs in the docstring
rather than in a design that works around it.

### One exemption: the def-form prefix

`defcfn` breaks Rule A on its face. Position two holds the C symbol when there
is no docstring and the docstring when there is one, and both are strings.

It is exempt, because `name docstring? attr-map?` is the convention of every
def form in Clojure, and a def form that looked like the others while placing
its docstring elsewhere would cause more mistakes than the shift it avoids.

The ambiguity is resolved structurally rather than by type. The parser anchors
on the first literal vector that is not a layout, takes the C symbol as the
element before it, and treats everything earlier as the prefix. With dynamic
argtypes there is no anchor, so it counts back three from the end. All four
legal shapes parse correctly, and the degenerate one points at itself:

```clojure
(defcfn e "Doc." at :size_t)   ; C symbol omitted
;; babashka.ffi: symbol not found: Doc.
```

The exemption covers that prefix and nothing else. It is not a general licence
for same-type shifts.

## The audit

Every public var, checked one at a time on 2026-08-28.

| Function | Arities after this decision | Verdict |
| --- | --- | --- |
| `segment` | `[addr]` `[addr size]` | unchanged |
| `reinterpret` | `[seg size]` `[seg size arena]` `[seg size arena cleanup]` | unchanged, new docstring |
| `slice` | `[seg offset]` `[seg offset len]` | unchanged, `len` also takes a layout |
| `find-symbol` | `[sym]` `[lib sym]` | unchanged |
| `cfn` | `[sym argtypes rettype]` `[lib sym argtypes rettype]` | unchanged |
| `defcfn` | `[name docstring? attr-map? sym argtypes rettype …]` | exempt |
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

Our own code barely used the other model. ffi-sqlite3 and the sqlite4clj port
are already arena-only with no `free` at all. ffi-brotli holds eighteen manual
allocations in three patterns, all lexically scoped, so one arena per function
replaces them and deletes eight `free` calls and a `run!`. ffi-duckdb has a
single genuinely non-lexical allocation, `pdb`, which lives from `open` until
`close!` and becomes a shared arena in the connection map.

The finding that decided it: across all four libraries, no `ffi/free` call
releases memory that a C function returned. Every one releases our own `alloc`.
Where foreign memory does appear, in ffi-duckdb's `varchar-at`, the code calls
duckdb's own `c-duckdb-free`. So `(alloc n)` and `free` were a closed loop that
existed to serve itself.

That loop also produced a bug found the same day. `free` decides what to accept
by comparing scopes, and memory from the global arena carries the global scope
exactly as C memory does, so the guard cannot tell them apart:

```clojure
(ffi/free (ffi/alloc (ffi/global-arena) 64))
;; accepted, and calls C free() on memory C never allocated
```

With one ownership model that mistake stops being something the API invites.
The guard still catches confined and shared arena memory, which is the common
error. Global arena memory remains indistinguishable and is documented rather
than engineered around.

`string->ptr` follows in the same decision, because it also handed out C memory
that needed `free`. It becomes `(string->ptr arena s)`, the arena leading under
Rule B, mapping directly onto `SegmentAllocator.allocateFrom(String)`, which is
what our own argument coercion already uses.

The cost lands on babashka rather than on the libraries: 42 `alloc` and 25
`free` calls in the test suite, a good number of which exist to exercise the C
allocator path, and two sections of `doc/ffi.md` that teach both models side by
side. Tests follow the API rather than deciding it.

### `write` moves its offset to the end

```clojure
;; before
(write p t v)
(write p t offset v)

;; after
(write p t v)
(write p t v offset)
```

It breaks Rule A, because position three holds a value in one arity and an
offset in the other and both are numbers. It breaks Rule B, because the offset
is optional, provides nothing, and belongs at the end.

The sharper argument came from seeing the rest of the API first. Every other
function that takes an offset puts it last. So a caller who knows
`(write p t v)` and wants to add an offset writes `(write p t v offset)`,
because that is what the API teaches everywhere else, and today that call
silently means something different.

The fix relocates part of the risk rather than removing it. Afterwards `v` and
`offset` sit next to each other and are both often numbers, so Rule C's limit
applies. That is still an improvement on two counts. The trap now only catches
someone using the four-argument form who transposes two of its own arguments,
which is the same irreducible risk `segment` and `slice` carry, instead of
catching everyone who knows the common form and extends it. And once `t` may be
a layout, `v` is a map, so the two stop sharing a type and a transposition
throws.

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
integers while their neighbours accept a layout.

Measured cost per call:

```
(sizeof :pointer)   keyword           48 ns
(sizeof point)      flat struct      554 ns
(sizeof rect)       nested struct   1565 ns
sizeof + alignof point              1133 ns   <- what callers write today
(alloc arena 8)     baseline         120 ns
```

The new form is about twice as fast as the workaround, because it resolves the
layout once. But resolving a layout still costs four to thirteen times the
allocation it precedes, because `layout-of` validates the structure, recurses
through the fields and builds a fresh map on every call.

So `layout-of` is memoized with a bounded cache, in the style of the
tail-shape cache. Invalidation is not a concern: a layout is an immutable
value, so it always resolves the same way, and the bound covers growth from
dynamically generated layouts. Hashing is not the bottleneck people expect:

```
hash of a hoisted layout             34 ns
hash of a fresh literal              33 ns
map lookup, hoisted key              42 ns
map lookup, fresh literal key       102 ns
```

Even the worst case is five times cheaper than recomputing.

We do not build `java.lang.foreign.MemoryLayout` objects, although that is the
JVM's own answer and coffi's. A `MemoryLayout` computes its size and alignment
once and stores them, so holding the object is the cache. It buys coffi more
than it would buy us: coffi passes structs through FFM downcalls, so its layout
object feeds the function descriptor directly, while our structs go through
libffi and need `ffi_type` descriptors. For us a `StructLayout` would serve
size and alignment only, and would add a second representation of a layout to
document.

coffi does not memoize either. `size-of` and `align-of` rebuild the layout on
every call unless the caller passes a `MemoryLayout` instance, and `defalias`,
where caching would naturally happen, delegates at call time:

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

That is a use-after-free turned into an exception. Requiring an arena would
force the caller to name one in the common case where it controls nothing,
which is a lie in the code rather than a safety gain.

The docstring said only what the arena does and nothing about what the form
without one means, which is the difference that matters. Agreed text:

```
Returns a view of segment seg with byte size size.

Without an arena the view has an unbounded lifetime. That is correct for
memory that C owns and that outlives your code.

With an arena the view is valid only while that arena is open, and the
runtime enforces it: a read after the arena closes throws. The arena calls
the optional cleanup function with the view when it closes, which is where
the deallocator of a C library belongs.

CAUTION: Give the real size. The runtime cannot check this claim, and a size
larger than the allocation turns every bounds check into a silent
out-of-bounds read.

CAUTION: Do not pass the view to C after the arena closes. The runtime stops
your own reads, but C can still reach the released memory.
```

## Alternatives considered

### Put the arena last, as coffi does

coffi is consistent: `(alloc size arena)`, `(alloc-instance type arena)`,
`(serialize obj type arena)`. Matching it would help people porting code.

Moot for `alloc`, which now requires an arena and has no optional leading
argument at all. It would still apply to `cfn` and `find-symbol`, and there it
is rejected: appending gives `(cfn sym argtypes rettype lib)`, which puts the
library three positions from the symbol it qualifies, and `clojure.core/resolve`
already prepends the scope in exactly this shape.

### Keep the Java order in `write` and drop the convenience arity

`(write p t offset v)` as the only form. Nothing is optional, so no position
shifts, Rule A holds, and the call matches `MemorySegment.set` exactly. This is
the most faithful answer to the FFM API.

Rejected on ergonomics. Almost every call site writes at offset zero, and this
form makes each of them carry a literal `0` that says nothing.

### Mirror the Java method, with the receiver first

This is the rule the code followed without saying so. `Arena.allocate` gives
`(alloc arena n)`, and `MemorySegment.reinterpret(size, arena, cleanup)` gives
`(reinterpret seg size arena cleanup)`.

Rejected as the stated rule, because it is invisible. A caller who does not
know the Java API cannot derive it, and it offers no answer for a function that
wraps no single Java method. Rule B reaches the same outcome and can be applied
by someone who has never read the FFM API.

### Add a `malloc` beside an arena-only `alloc`

Splitting by name would split by ownership model, which is the thing that
matters. JNA does exactly this, with `Native.malloc` and `Native.free` beside
its garbage-collected `Memory`.

Rejected because that split belongs to the generation before Panama. The
libraries built on FFM dropped it, since the arena subsumes the use case, and
the rare case where C takes ownership of memory we allocated is one `cfn` line
away and deserves to be visible at the call site.

## How this compares to the FFM API

Every rule agrees with FFM except the change to `write`. `Arena.allocate(size,
alignment)` puts the arena in the receiver position, which is `(alloc arena n
alignment)`. `MemorySegment.reinterpret(size, arena, cleanup)` keeps the
segment as the receiver. `MemorySegment.get(layout, offset)` is `(read p t
offset)`.

Then `MemorySegment.set(layout, offset, value)` puts the offset before the
value, and our `write` no longer does. Java can afford that order because `set`
has no arity that omits the offset, so no position in it ever changes meaning.
The hazard is not the order but a convenience arity combined with that order,
and the convenience arity is ours.

coffi made the same two choices we originally did:

```clojure
(defn read-long  ([segment] ...) ([segment offset] ...))
(defn write-long ([segment value] ...) ([segment offset value] ...))
```

Its reads append the offset and are safe; its writes insert it, so position two
holds a value in one arity and an offset in the other, both longs.
`write-longs` repeats the shape. So fixing `write` moves us away from coffi,
but not away from a decision coffi argued for: two libraries mirrored
`MemorySegment.set` and inherited a hazard the Java method does not have.

## Code changes this decision calls for

- `alloc`: remove the one-argument form, and accept a layout wherever it
  accepts a type keyword.
- `write`: `[p t v]` and `[p t v offset]`.
- `slice`: accept a layout as the length.
- `string->ptr`: becomes `[arena s]`.
- `layout-of`: memoize with a bounded cache.
- `reinterpret`: replace the docstring with the text above.
- `free`: rewrite the docstring around memory a C function returned, and state
  that a pointer from the global arena cannot be distinguished from one the C
  allocator returned.
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

These are not ordering questions and are on the project list rather than here:
reading and writing structs through a layout, which is the natural completion
of struct support; `ptr->string` refusing a zero-size segment even though our
own `string-at` reads unbounded behind every `:string` return, which is what
drives the `Long/MAX_VALUE` call in ffi-duckdb; `reinterpret`'s cleanup
argument, which no library uses although it is the arena-native way to attach a
C deallocator; and `write-bytes` always writing the whole array.

## Sequencing

The source is moving to the babashka/ffi repository. These changes are breaking
for anyone using the four-argument `write` or the one-argument `alloc`, so they
land before the library's first release, and the changelog says so.
