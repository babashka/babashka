# ADR 0004: Argument order in babashka.ffi

## Status

Accepted 2026-08-27. Recorded on branch `ffi-arg-order-adr`. The audit below is
complete. The one code change that follows from it is not made yet.

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
number there. A caller who swaps two numbers gets no exception and no crash,
only wrong memory. This rule outranks every convenience.

Arguments of clearly different types may share a position, because a mistake
throws immediately. Passing an arena where a byte count belongs fails with
"alloc takes an integer byte count or a type keyword".

### Rule 3: an optional argument goes at the end, unless it owns what follows it

The default is to append. A leading position is reserved for an argument that
is the scope or owner of the argument after it, in the way a namespace owns a
symbol. Two such scopes exist:

- A library owns a symbol, so `cfn` and `find-symbol` take the library first.
- An arena owns an allocation, so `alloc` takes the arena first.

An arena that is not the allocator is an ordinary argument and is appended.
`reinterpret` attaches a lifetime to a segment that already exists, so the
segment leads and the arena follows it.

## The audit

Taken from the clj-kondo analysis of the public vars, 2026-08-27.

| Function | Arities | Verdict |
| --- | --- | --- |
| `segment` | `[addr]` `[addr size]` | appends |
| `reinterpret` | `[seg size]` `[seg size arena]` `[seg size arena cleanup]` | appends |
| `slice` | `[seg offset]` `[seg offset len]` | offset is required here, and `len` appends |
| `find-symbol` | `[sym]` `[lib sym]` | scope leads, rule 3 |
| `cfn` | `[sym argtypes rettype]` `[lib sym argtypes rettype]` | scope leads, rule 3 |
| `alloc` | `[n]` `[arena n]` `[arena n alignment]` | scope leads, rule 3 |
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
teaches, means "write 42 at offset 8". Both arguments are numbers, so nothing
throws. The memory is simply wrong.

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
