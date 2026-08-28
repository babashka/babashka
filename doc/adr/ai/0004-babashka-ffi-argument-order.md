# ADR 0004: Argument order in babashka.ffi

## Status

Accepted 2026-08-27. Recorded on branch `ffi-arg-order-adr`. The audit below is
complete. The one code change that follows from it is not made yet.

## Walkthrough decisions, in progress

Recorded one at a time during the review of 2026-08-28. When the walkthrough
finishes this section replaces the rules and the audit below it.

### Confirmed

Rule C. When two adjacent parameters could be swapped, keep the sets of types
they accept disjoint, so that at least one direction of the swap throws. This
covers the case Rule A does not: Rule A forbids a position from changing
meaning between arities, but says nothing about two parameters of the same type
standing next to each other inside one arity.

The alignment argument of `alloc` stays an integer, and that is the worked
example of Rule C. Its neighbour `n` is polymorphic and takes a byte count or a
type keyword, so a swapped call throws:

```clojure
(ffi/alloc arena :pointer 64)  ; a pointer-sized block on a 64-byte boundary
(ffi/alloc arena 64 :pointer)  ; babashka.ffi: alloc takes an integer alignment
```

Letting alignment take a type as well would make both calls valid and mean
different things, which manufactures the hazard instead of catching it. An
alignment that does come from a type is already available as `(alignof t)`,
which returns an integer and composes: `(alloc arena 80 (alignof :pointer))`
allocates room for ten pointers.

Rule C has a limit worth stating. It only bites when one of the two parameters
carries a type. Two integers side by side, as in `(alloc arena 64 8)`, cannot be
told apart, and neither can the address and size of `segment`. That residual
risk is inherent to a positional API.

### Settled: `segment`

`[addr]` and `[addr size]`, unchanged. Rule A holds because nothing shifts
position. Rule B holds because `size` is optional and appended. Rule C cannot
apply, because an address and a byte count are both numbers and no type
distinction is available.

The residual risk is therefore real and goes in the docstring rather than being
designed around: `(segment 8 addr)` builds a valid-looking pointer to address 8
with an enormous size, throws nothing, and fails later at the first read, in a
native image by taking the process down.

### Settled: `reinterpret`

`[seg size]`, `[seg size arena]` and `[seg size arena cleanup]`, unchanged.
Every position keeps its meaning across all three arities, the optional
arguments are appended, and no adjacent pair shares a type. The arity structure
also encodes a real dependency: `cleanup` cannot be passed without `arena`,
because the arena is what calls it.

The arena stays optional because the two forms make different and equally valid
claims about ownership. Without an arena you get a window onto memory that C
owns and that outlives your code. With an arena you bind the foreign pointer's
validity to that arena, and the runtime then enforces it, which is worth more
than the convenience:

```clojure
(def view (with-open [a (ffi/confined-arena)] (ffi/reinterpret p 6 a)))
(ffi/ptr->string view)
;; babashka.ffi: the pointer at address 105553138057360 belongs to a closed arena
```

That is a use-after-free turned into an exception. Requiring an arena would
force the caller to name one in the common case where it controls nothing,
which is a lie in the code rather than a safety gain.

The docstring changes. It currently explains only what the arena does and says
nothing about what the form without an arena means, which is the difference
that matters. Agreed text:

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

The first caution is new and covers what makes this function fundamentally
unsafe: `size` is a claim the runtime cannot verify. The second one existed
already, and gains the distinction that makes it comprehensible, which is that
the runtime stops your reads but not C's.

Two problems found next to `reinterpret` are not `reinterpret`'s fault and are
tracked separately: the cleanup argument is used nowhere in our libraries, and
`ptr->string` refuses a zero-size segment even though our own `string-at` reads
unbounded behind every `:string` return, which is what drives the
`Long/MAX_VALUE` call in ffi-duckdb.

### Settled: layouts as a size argument, and memoizing `layout-of`

`alloc` takes a layout where it takes a type keyword, and `slice` takes one
where it takes a length:

```clojure
(alloc arena [:struct [[:x :int] [:y :int]]])
(slice arr (* i (sizeof point)) point)
```

Today `sizeof` and `alignof` accept a layout but `alloc` and `slice` refuse
one, so the caller writes `(alloc arena (sizeof point) (alignof point))`. That
walks the layout tree twice, and writing an alignment by hand is the step this
is meant to remove.

It also strengthens Rule C. `offset` and `alignment` stay integers while their
neighbours accept a layout, so a swapped call throws.

Measured cost per call, 2026-08-28:

```
(sizeof :pointer)   keyword           48 ns
(sizeof point)      flat struct      554 ns
(sizeof rect)       nested struct   1565 ns
sizeof + alignof point              1133 ns   <- what callers write today
(alloc arena 8)     baseline         120 ns
```

So the new form is about twice as fast as the workaround, because it resolves
the layout once. But resolving a layout still costs four to thirteen times the
allocation it precedes, because `layout-of` validates the structure, recurses
through the fields and builds a fresh map on every call.

Therefore `layout-of` is memoized, with a bounded cache in the style of the
tail-shape cache. Invalidation is not a concern: a layout is an immutable
value, so the same layout always resolves the same way, and the only risk is
growth from dynamically generated layouts, which the bound covers. Hashing is
not the bottleneck people expect it to be:

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
libffi and need `ffi_type` descriptors instead. For us a `StructLayout` would
serve size and alignment only, and would add a second representation of a
layout to document.

Worth noting that coffi does not memoize either. `size-of` and `align-of`
rebuild the layout on every call unless the caller passes a `MemoryLayout`
instance, and `defalias`, which is where caching would naturally happen,
delegates at call time rather than resolving once:

```clojure
(defmethod c-layout ~new-type [_type#] (c-layout aliased#))
```

### Settled: `find-symbol`, and Rule B's exception has a precedent in core

`[sym]` and `[lib sym]`, unchanged. It reads as "find this symbol in this
library", and a swapped call throws with a message that names the problem:
`:library must be a library map, a function that returns one, or a delay, atom
or var that holds one, got "pow"`.

Rule B's exception is not our invention. Clojure core already does exactly
this, and reserves the leading position for a scope in its lookup functions
while appending ordinary optional arguments elsewhere:

```
resolve      ([sym] [env sym])
ns-resolve   ([ns sym] [ns env sym])
get          ([map key] [map key not-found])
subs         ([s start] [s start end])
```

`resolve` has the shape of `find-symbol` exactly. So the rule describes
existing practice rather than a preference of ours.

Core is not spotless here: `ns-resolve` inserts `env` in the middle, so `sym`
moves from position two to position three. That is the construction Rule A
tolerates only because the types differ.

### Settled: `cfn`

`[sym argtypes rettype]` and `[lib sym argtypes rettype]`, unchanged. It is the
function Rule B's exception actually rests on, because appending would give
`(cfn sym argtypes rettype lib)` and put the library three positions away from
the symbol it qualifies.

Adding the library shifts the whole tail by one, so every position holds
something different between the two arities. That is safe because the arity is
chosen by argument count, not by type, and because the types are disjoint at
every position but one. The exception is position three, where a `rettype` may
be a layout vector and an `argtypes` is always a vector. Those two are also
adjacent, so Rule C applies, and a swap is caught:

```clojure
(cfn "div" [:struct [[:quot :int] [:rem :int]]] [:int :int])
;; babashka.ffi: unknown type :struct
```

Two observations recorded without acting on them. Omitting the library falls
back silently to searching every loaded library and then the system lookup,
which is documented behaviour and is inherent to the argument being optional
rather than to its position. And symbol resolution is deferred to the first
call, which is necessary because a `:library` may be a delay or a var that is
not ready at bind time, as the bring-your-own-dynlib pattern requires. The
error is clear when it arrives: `babashka.ffi: symbol not found: zlibVersion`.

### Code changes this walkthrough has collected

Applied together when the walkthrough ends, so that the branch stays
documentation until then and the code lands in one tested commit.

- `reinterpret`: replace the docstring with the text agreed above. No change to
  the arguments.
- `segment`: document in the docstring that an address and a size are both
  numbers, so a swapped call builds a valid-looking pointer that fails later.
- `alloc`: accept a layout wherever it accepts a type keyword.
- `slice`: accept a layout as the length.
- `layout-of`: memoize with a bounded cache.
- `cfn`: name the cause when a layout vector appears on an argtypes position.
  Today that reports `unknown type :struct`, which describes the symptom.

## Context

The API is positional. Several functions have optional arguments, so the same
position can hold different things in different arities. `alloc` takes an arena
before the size, `read` takes an offset after the type, and `cfn` takes a
library before the symbol.

Without a written rule each new function is a coin flip, and the reviewer has
nothing to check against. Worse, a caller who guesses the order wrong does not
always get an error.

This decision states the rule, and audits the whole public API against it.

## Decision

Three rules, in order of precedence.

### Rule 1: the byte offset is always the final argument

Every function that reads or writes at an offset puts that offset last, and
omitting it means offset zero.

### Rule 2: two arguments that can hold the same kind of value never trade places

If position 3 holds a number in one arity, no other arity may put a different
number there. This rule outranks every convenience, and it is the only rule
that can condemn a function that already exists.

A position may hold different things across arities when the types differ,
because then a mistake throws. This is the whole test, and it is decided by
what happens to a caller who guesses wrong, not by taste:

```clojure
(ffi/alloc 8 arena)
;; babashka.ffi: alloc takes an integer byte count or a type keyword,
;; got #object[jdk.internal.foreign.ArenaImpl ...]

(ffi/write p :int64 3 7)
;; no exception. Writes 7 at offset 3, where the caller meant 3 at offset 7.
```

Bounds checks catch an offset that falls outside the segment, so a mistake on a
small allocation does throw. It stays silent when both numbers are valid
offsets into the segment, which is exactly the array and struct case that the
offset arity exists for.

### Rule 3: an optional argument goes at the end, unless it provides what follows it

This rule settles where to put a new optional argument. It is a tiebreaker for
functions we have yet to write, not a defence of the ones we have: every
function in the audit that rule 3 permits is already permitted by rule 2 on its
own.

The default is to append. A leading position is reserved for the argument that
says where the next value comes from:

- A library provides a symbol, so `cfn` and `find-symbol` take the library first.
- An arena provides an allocation, so `alloc` takes the arena first.

Providing is narrower than owning, and the difference decides `reinterpret`.
Its arena does not provide the segment, which already exists; it only attaches
a lifetime to it. So the segment leads and the arena is appended.

## The audit

Taken from the clj-kondo analysis of the public vars, 2026-08-27.

| Function | Arities | Verdict |
| --- | --- | --- |
| `segment` | `[addr]` `[addr size]` | appends |
| `reinterpret` | `[seg size]` `[seg size arena]` `[seg size arena cleanup]` | appends |
| `slice` | `[seg offset]` `[seg offset len]` | offset is required here, and `len` appends |
| `find-symbol` | `[sym]` `[lib sym]` | scope leads, rule 3 |
| `cfn` | `[sym argtypes rettype]` `[lib sym argtypes rettype]` | scope leads, rule 3 |
| `alloc` | `[n]` `[arena n]` `[arena n alignment]` | passes rule 2, provider leads under rule 3 |
| `free` | `[p]` | takes no arena, and refuses arena memory |
| `read` | `[p t]` `[p t offset]` | offset last, rule 1 |
| `write` | `[p t v]` `[p t offset v]` | BREAKS rules 1 and 2 |
| `read-bytes` | `[p n]` `[p n offset]` | offset last, rule 1 |
| `write-bytes` | `[p arr]` `[p arr offset]` | offset last, rule 1 |
| `callback` | `[f argtypes rettype]` | one arity |
| `byte-buffer`, `string->ptr`, `sizeof`, `alignof`, `address`, `size`, `pointer?`, `ptr->string`, `null?`, `free-callback`, `load-library`, `load-system-library`, the four arena constructors | one arity each | nothing to order |

One function breaks the rules, and it is the one where a mistake is silent.

## Consequences

`write` changes so that the value keeps its position and the offset moves to
the end:

```clojure
;; before
(write p t v)
(write p t offset v)

;; after
(write p t v)
(write p t v offset)
```

Today `(write p :int64 42 8)` means "write 8 at offset 42". A caller who reads
the offset as the trailing option, which every other function in the API
teaches, means "write 42 at offset 8". Both arguments are numbers, so as long
as both fall inside the segment nothing throws, and the wrong value lands at
the wrong place in the caller's own buffer.

`write` is the only function that rule 2 condemns. `alloc`, `cfn` and
`find-symbol` also change what a position means between arities, but the types
differ there, so a wrong guess throws before it reaches memory.

After the change the offset is the last argument in `read`, `write`,
`read-bytes`, `write-bytes` and `slice`, with no exceptions.

The migration is small, because almost every call uses the three-argument form
already:

```clojure
;; unchanged, all of ffi-brotli, ffi-sqlite3 and the sqlite4clj port
(ffi/write slot :int64 id)

;; ffi-duckdb close!, where the explicit zero is now redundant
(ffi/write pconn :pointer 0 conn)   ; before
(ffi/write pconn :pointer conn)     ; after

;; babashka's own ffi tests, which use the offset deliberately
(ffi/write p :bool 1 false)         ; before
(ffi/write p :bool false 1)         ; after
```

`alloc`, `cfn` and `find-symbol` do not change.

## How this compares to coffi and to the FFM API

Checked against both, 2026-08-27.

The FFM API agrees with every rule except the one change we make. `Arena.allocate(size, alignment)` puts the arena in the receiver position, which
is `(alloc arena n alignment)`. `MemorySegment.reinterpret(size, arena, cleanup)` keeps the segment as the receiver, which is `(reinterpret seg
size arena cleanup)`. `MemorySegment.get(layout, offset)` is `(read p t
offset)`.

Then `MemorySegment.set(layout, offset, value)` puts the offset before the
value, and our new `write` does not. That divergence is deliberate, and the
reason is that Java can afford this order while we cannot: `set` has no arity
that omits the offset, so no position in it ever changes meaning. The hazard is
not the order. The hazard is a convenience arity combined with that order, and
the convenience arity is ours, not Java's.

coffi made the same two choices we did, independently:

```clojure
;; coffi.mem
(defn read-long  ([segment] ...) ([segment offset] ...))
(defn write-long ([segment value] ...) ([segment offset value] ...))
```

Its reads append the offset and are safe. Its writes insert it, so position two
holds a value in one arity and an offset in the other, both longs. `write-longs` repeats the shape with `[segment n value]` and `[segment n
offset value]`.

So fixing `write` moves us away from coffi, but not away from a decision coffi
argued for. Two libraries mirrored `MemorySegment.set` and inherited a hazard
that the Java method does not have, because the Java method has no optional
offset. Being right is worth more here than matching, and the same reasoning
applies to the arena: coffi appends it, we lead with it, and both are defensible
while only one of them is ours to keep consistent.

## Alternatives considered

### Put the arena last, as coffi does

coffi is consistent about this: `(alloc size arena)`, `(alloc-instance type
arena)`, `(serialize obj type arena)`. Matching it would help people porting
code.

Rejected, because it would split our own API. The library leads in `cfn` and
`find-symbol` for the same reason the arena leads in `alloc`: it is the scope
that owns the next argument. Moving only the arena would leave two spellings of
one idea. coffi parity matters most for `defcfn`, which people write by hand
every day and which we already match exactly.

The cost is contained. A coffi habit produces `(alloc 8 arena)`, which throws
at once with a message that names the problem.

### Keep the Java order and drop the convenience arity

`(write p t offset v)` as the only form. Nothing is optional, so no position
ever shifts, rule 2 holds, and the call matches `MemorySegment.set` exactly.
This is the most faithful answer to the FFM API.

Rejected on ergonomics. Almost every call site in our own libraries writes at
offset zero, and this form makes each of them carry a literal `0` that says
nothing. The rule we adopt keeps the short form and still satisfies rule 2.

### Mirror the Java method, with the receiver first

This is the rule the code follows today without saying so. `Arena.allocate`
gives `(alloc arena n)`, and `MemorySegment.reinterpret(size, arena, cleanup)`
gives `(reinterpret seg size arena cleanup)`.

Rejected as the stated rule, because it is invisible. A caller who does not
know the Java API cannot derive it, and it offers no answer for a function that
wraps no single Java method. Rule 3 keeps the same outcome for these two
functions and can be applied by someone who has never read the FFM API.

### Keep the default allocator as it is

Not a change, but worth recording. `(alloc n)` without an arena returns memory
from the C allocator that the caller releases with `free`. coffi's `(alloc
size)` uses an automatic arena instead.

Ours stays. Forgetting `free` leaks, and a leak is a safer failure than the one
an automatic arena invites, where the collector releases memory that C still
holds.

## Sequencing

The source is moving to the babashka/ffi repository on branch `ffi-submodule`.
Make the `write` change wherever the source lives when the work starts, and
make it before the first release of the library, because it changes the meaning
of a call that compiles either way.
