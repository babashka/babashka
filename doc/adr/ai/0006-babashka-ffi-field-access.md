# ADR 0006: A place: one member of a layout, resolved once

## Status

Accepted 2026-08-29. First implemented as `field-reader`/`field-writer`
(babashka/ffi#20), then reshaped the same day into `place` on branch
`place` of babashka/ffi (d294149), which is the decision below. Builds on ADR 0003 (structs by
value), ADR 0004 (argument order) and ADR 0005 (unions), and on the array
layouts merged in babashka/ffi#14.

## Context

A layout describes memory: `[:struct ...]`, `[:array ...]`, `[:union ...]`.
`read` and `write` work on a whole layout: a struct reads as a map of every
member and writes from one. Touching one member has two spellings today,
and both are the classic FFI mistakes:

```clojure
(def bone [:struct [[:name [:array :char 32]] [:parent :int]]])   ; raylib BoneInfo

(:parent (ffi/read p bone))     ; decodes all 33 slots to use one
(ffi/read p :int 32)            ; offset and type by hand: 28, or :long, reads garbage silently

(ffi/write p bone (assoc (ffi/read p bone) :parent 3))   ; read all, write all, for 4 bytes
(ffi/write p :int 3 32)                                  ; offset by hand again
```

A layout exists so that offsets and types are computed once from a
description. Whole-value access gets that; single-member access does not.
The gap matters for the configuration-struct pattern (SDL, curl, libuv
fill a struct, the program adjusts two fields and hands it back) and for
unions, where the member name is the only thing that fixes the type
(ADR 0005).

## Decision

One function, `place`, and no new verbs. It takes a layout and a member
name, or a path of member names and array indices, and returns a **place**:
the member resolved once into its offset and its codecs. Without a path,
the place is the whole layout. `read` and `write` take a place where they
take a type keyword or a layout:

```clojure
(def parent (ffi/place bone :parent))          ; resolved once: offset 32, :int

(ffi/read p parent)                            ;=> 7      the bare form
(ffi/write p parent 3)                         ;           the assignment
(ffi/read arr (ffi/place point :y) (* i 8))    ; the byte-offset arity composes: stride an array of structs
(ffi/read p (ffi/place point))                 ; the whole layout, its lookup done once
(ffi/write q (ffi/place outer [:msgs 1 :data :result]) 9)   ; through an array and a union
```

The direction stays in the verb. This is Common Lisp's model of a place
(CLHS 5.1, "Generalized Reference"): `(car x)` is the form you read, and
`(setf (car x) v)` the assignment to the same place. Here `read` is the
form, `write` is `setf`, and a place is what both act on. FFM has the same
shape: `layout.varHandle(path...)` is a value, and `get`/`set` on it are
the verbs.

There is no per-call form and no function that resolves per call. The
rule, stated in ADR 0007: **a function with a resolve step is made once
and kept, and has no per-call twin.** A one-off member access is a `let`.

Semantics:

- The offset is the sum of the member offsets along the path; the type is
  the type at the end. Both come from the resolved layout.
- The value at the end uses the same decoder or encoder that `read` and
  `write` use for that type, so a struct reads as a map, an array as a
  vector, a union as a pointer, and a wrong value gets the same error,
  with the path in it (`at [:msgs 1 :data], ...`).
- Through a union, the path element **is** the tag. `[:data :result]`
  names the member, so `write-field` needs no pair on this route:
  `(write-field p curl-msg [:data :result] 0)` and
  `(write p curl-msg {... :data [:result 0]})` write the same bytes.
- A bad path is an error when the place is made, naming the problem:
  no such member, an index past the array's count, a path that continues
  past a scalar. Not `nil`, as
  `get-in` would give: a map's keys are open, a layout's members are
  closed, so a member that is not there is a mistake in the program, and
  `nil` could not be told from a `0` that was read.
- Resolution is cached per `[layout path]`, bounded as the layout and codec
  caches are, so making a place in a `let` costs one lookup and using it
  costs none: `read` sees the place's type and calls its baked decoder.

Argument order follows ADR 0004: the pointer is the subject and comes
first, the layout next as in `read` and `write`, the place after it, and
for a write the value last. The place and the value are of disjoint types
(rule C).

## Weighed on correctness, performance, growth, and tradition

**Correctness.** Offset and type come from the layout, so the two errors
the hand form invites cannot happen: a wrong offset reading garbage
silently, and a re-stated type. A bad path fails by name. The leaf reuses
the existing codecs, so nothing is checked differently from `write`.
Reads stay bounds-checked by the segment.

**Performance.** Measured on the JVM, best of five runs of two million:

    read p (place bone :parent)                3.5 ns   the 33-slot struct's one member
    read q (place outer [:msgs 1 :data :result]) 5.9 ns
    read pt (place point), the whole layout     16 ns   against 127 for (read pt point) per call
    write p parent 3                           8.8 ns   (write :int 3 32 by hand: 5.0)
    read :int 32, the offset by hand           1.7 ns   the JIT folds a constant offset
    the closure form this replaced            6-19 ns   (JIT-dependent)

A place through `read` is cheaper than the closure form was: the `case`
default plus one `instance?` costs less than a closure call.

Native image, where nothing folds, measured on the place build:

    read :int 32, the offset by hand           98 ns
    read p (place bone :parent)               105 ns   at the hand read
    read q (place outer [:msgs 1 :data :result]) 103 ns   depth is free once resolved
    read pt point, per call                   359 ns   a two-int struct
    read pt (place point), the whole layout   154 ns
    (:parent (read p bone)), the 33-slot struct 1466 ns The accessor is never
worse than a per-call form at any use count, which is what made the
per-call form unnecessary. No new `ValueLayout` access site. An accessor
form, `(field-reader layout path)` returning a function the way `cfn`
hoists its work, would be faster still in a hot loop; it is additive and
deferred until a benchmark asks for it.

**Growth.** Two new vars; nothing changes shape. One signature covers a
name and a path. Byte-offset addition, the accessor form, and a
"several members into a map" function can all be added later without
touching these two.

**Tradition.** Every FFI with named layouts reads and writes a member by
name, and the two that model layouts as data also model the path as data.
The resolve-once shape is the JDK's own: `layout.varHandle(path...)` is
obtained once and then used, and it is what `cfn` already does here:

| prior art | read by name | write by name | nested path |
|---|---|---|---|
| JNA 5.16 (`com.sun.jna.Structure`, from the jar) | `readField("x")` | `writeField("x", v)` | no |
| Java FFM | `layout.varHandle(groupElement("x"))` | same handle | `PathElement` chains, `sequenceElement(i)` |
| Ruby FFI (`Struct.c:357/383`) | `s[:x]` | `s[:x] = v` | chained `[]` |
| Python ctypes (run) | `d.fd` | `d.fd = v` | attribute chain |
| Python cffi | `p.fd` | `p.fd = v` | attribute chain |
| Haskell hsc2hs | `#{peek s, x} p` | `#{poke s, x} p v` | member designator |
| dtype-next (`struct.clj:170-200`) | `(get s :x)` | in place | `(get s [:xs 2])` |
| C | `offsetof(s, x)` | | `offsetof(s, a.b)` |
| coffi (`mem.clj:1593`) | `struct-field-offset` gives the offset | | no |

The names are JNA's verbs, which are also this API's own (`read`,
`write`). The path is the JDK's `PathElement` and dtype-next's vector,
resolved by FFM to the same byte: `byteOffset` of
`[:msgs 1 :data :result]` on the layouts above is 48, and so is ours.

## Alternatives not taken

- **`field-reader` / `field-writer`, functions that access.** Shipped as
  babashka/ffi#20 and reshaped the same day. A function per direction
  hides the direction (`(parent p)` reads, `(parent p 3)` would write),
  and "field reader" stopped naming the thing once the whole layout became
  a legal target. A place as a value, with the verbs kept, fixes both.
- **The name.** `reader`/`writer` name the plumbing, not the thing;
  `lens` is a pure abstraction and this mutates memory, the same objection
  ADR 0006 raised against `get`/`assoc-in`; `path` names the argument, not
  the result, and collides with `babashka.fs/path` in every script;
  `accessor` is a function that accesses, and a place is not called. A
  place is the Common Lisp word for exactly this, mutation included.
- **A per-call form, `(read-field p layout path)` / `(write-field p layout
  path v)`.** Built first, then removed before merge. Measured: a `let`
  that makes the accessor and uses it once costs the same, and every
  further use is nearly free, so the per-call form had no case where it
  won. It would also have been the one function in the namespace that
  resolves on every call, against `cfn`. See the rule under Decision.
- **`get` / `get-in` / `assoc-in`.** The pure-data names for an effectful
  operation on native memory, with a signature that cannot match
  (`(get m k)` has no layout and has a not-found arity), and they shadow
  `clojure.core` for anyone who `:refer`s them. The API already says
  `read` and `write` for memory; these extend the same verbs.
- **`get-field` / `set-field`.** Suggested in review. `get`/`set` is the
  accessor pair for object fields (beans, `setX`); `read`/`write` is the
  pair this API already uses for memory, where bounds and scope apply. A
  second vocabulary for the same operation is the cost, and JNA made the
  same choice for the same reason (`readField`/`writeField`).
- **A depth split, `read-field` and `read-fields`.** Clojure splits `get`
  and `get-in` because a vector is a legal map key; in a layout a member
  is always a keyword and an index always an integer, so a vector can only
  be a path. FFM and dtype-next use one entry point for any depth.
- **`read-fields` as select-keys.** A different operation, and a vector
  already means a path here, so it would need its own name. Left out
  until a wide struct is decoded for two members in a hot loop.
- **Field-interop syntax, `(.-x u)`.** Suggested by a reader of ADR 0005.
  It needs a JVM class per layout, which a native image cannot generate
  and a data layout does not have. The goal behind it, the type from the
  layout rather than re-stated, is what this decision delivers.

## Consequences

- The last place the API says "compute the offset yourself" goes away.
- `read` and `write` accept three things in the type slot: a keyword, a
  layout, a place. A place is the resolved form of the other two.
- The API has a stated rule for resolve-once functions, which any later
  addition follows: no per-call twin.
- `babashka.impl.ffi` exposes the two vars through `copy-ns`; the
  library's API.md test and bb's parity test guard the surface.
- The guide's union section shows `read-field` as the normal way to read
  a member and `(ffi/read u :int)` as the escape hatch.
