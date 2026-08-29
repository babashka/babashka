# ADR 0006: Read and write one member of a layout by name or path

## Status

Proposed 2026-08-29. Not implemented. Builds on ADR 0003 (structs by
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

Two functions. The place is a member name, or a path of member names and
array indices; a name is a one-element path.

```clojure
(ffi/read-field p layout path)          ; the value of that member, decoded as its type
(ffi/write-field p layout path v)       ; writes v there, encoded as its type; returns nil

(ffi/read-field p bone :parent)                       ;=> 7
(ffi/read-field p curl-msg [:data :result])           ; struct offset 16, union member :result, as :int
(ffi/read-field p outer [:msgs 1 :data :result])      ; through an array index
(ffi/write-field p outer [:msgs 1 :data :result] 0)
(ffi/write-field p outer [:msgs 1] {:msg 1 :easy nil :data [:result 0]})   ; a non-leaf: the struct's encoder
```

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
- A bad path is an error naming the problem: no such member, an index past
  the array's count, a path that continues past a scalar.
- Resolution is cached per `[layout path]`, bounded as the layout and codec
  caches are, so a call is a lookup plus the slot access.

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

**Performance.** One field instead of the whole struct: for the 33-slot
`BoneInfo`, one slot read instead of a 179 ns decode (JVM). Path
resolution is map lookups over the resolved layout, cached; no new
`ValueLayout` access site, so no image cost beyond the walk. An accessor
form, `(field-reader layout path)` returning a function the way `cfn`
hoists its work, would be faster still in a hot loop; it is additive and
deferred until a benchmark asks for it.

**Growth.** Two new vars; nothing changes shape. One signature covers a
name and a path. Byte-offset addition, the accessor form, and a
"several members into a map" function can all be added later without
touching these two.

**Tradition.** Every FFI with named layouts reads and writes a member by
name, and the two that model layouts as data also model the path as data:

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
- `babashka.impl.ffi` exposes the two vars through `copy-ns`; the
  library's API.md test and bb's parity test guard the surface.
- The guide's union section shows `read-field` as the normal way to read
  a member and `(ffi/read u :int)` as the escape hatch.
