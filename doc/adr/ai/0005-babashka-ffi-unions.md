# ADR 0005: C unions in babashka.ffi

## Status

Accepted 2026-08-29. Implemented on branch `union-layouts` of babashka/ffi
(e646d0b). Builds on ADR 0003 (structs by value) and ADR 0004 (argument
order), and on the array layouts merged in babashka/ffi#14.

## Context

`babashka.ffi` describes memory with layouts: `[:struct [[name type] ...]]`
and `[:array type n]`. The array order, element type before count, follows
coffi and C declaration order. jolt and FFM's `sequenceLayout` put the
count first. A C union cannot be described. A survey of twenty
headers on this machine (macOS SDK, Homebrew: duckdb, curl, SDL2, libuv,
glib, and the Linux and Windows headers through GitHub) found unions in
every event and IO API:

| library | union | where the caller learns which member is live |
|---|---|---|
| curl | `CURLMsg.data` (`multi.h:100`) | `msg`, a sibling field of the enclosing struct |
| Linux | `epoll_data_t` (`sys/epoll.h:77`) | nowhere: the caller registered the fd or the pointer |
| SDL2 | `SDL_Event` (`SDL_events.h:655`), 29 members | `type`, the first field of every member |
| duckdb | `duckdb_string_t.value` (`duckdb.h:429`) | `length <= 12`, a predicate on a shared field |
| libuv | four unions in `uv.h` (472, 1059, 1221, 1225) | context |
| POSIX | `in6_addr`, `sigaction`, `ifreq` | context |

Two facts from this table decide the design.

First, the tag that says which member is live is **outside the union** in
half the cases, or does not exist. `CURLMsg` keeps it in a sibling field.
`epoll_data_t` has none: the program knows because it wrote the value. A
union type therefore cannot, in general, decode itself.

Second, none of these unions is passed **by value** through a function.
`curl_multi_info_read` returns a pointer to a `CURLMsg`. `epoll_wait` fills
an array of `epoll_event`. `SDL_PollEvent` takes a pointer. duckdb hands out
`duckdb_string_t` inside a vector's data pointer. The value of a union
layout is size and alignment, so that a struct that holds one lays out
correctly, and a way to read the member the program knows applies.

`duckdb_string_t` matters to this project directly: it is how duckdb's
vector API hands out strings, and `babashka.duckdb` currently avoids it by
calling `duckdb_value_varchar` per cell, which allocates.

## Decision

### The layout

```clojure
[:union [[name type] ...]]
```

Members are `[name type]` pairs, as in a struct. `type` is a type keyword
or a layout. Size is the size of the largest member, rounded up to the
alignment; alignment is the strictest member's. This is the C rule, so a
struct that holds a union gets the offsets the compiler gives it.

```clojure
(def curl-msg
  [:struct [[:msg :int]
            [:easy :pointer]
            [:data [:union [[:whatever :pointer] [:result :int]]]]]])

(ffi/sizeof curl-msg)   ;=> 24
```

### Reading

`read` of a union returns **a pointer to its bytes**, sized to the union.
The caller reads the member it knows applies. The C constants in the
examples are enum values from the headers, which a binding defines as
numbers:

```clojure
(def CURLMSG_DONE 1)       ; curl/multi.h:92, enum CURLMSG: NONE 0, DONE 1
(def SDL_KEYDOWN 0x300)    ; SDL_events.h:98
(def SDL_MOUSEMOTION 0x400) ; SDL_events.h:108

;; the tag is a sibling field
(let [{:keys [msg data]} (ffi/read p curl-msg)]
  (when (= msg CURLMSG_DONE)
    (ffi/read data :int)))                      ; the :result member

;; there is no tag: the program knows
(let [{:keys [events data]} (ffi/read ev epoll-event)]
  (ffi/read data :int))                         ; it registered an fd

;; the tag is the first field of every member
(let [u (ffi/read p sdl-event)]
  (case (ffi/read u :uint32)
    SDL_KEYDOWN (ffi/read u key-event)
    SDL_MOUSEMOTION (ffi/read u mouse-event)
    nil))
```

Nothing is decoded that the caller did not ask for. Reads through the
returned pointer are bounds-checked against the union's size.

### Writing

`write` of a union takes a **pair**, the member and its value:

```clojure
(ffi/write p curl-msg {:msg 1 :easy h :data [:result 0]})
```

The same bytes are a pointer as `:whatever` and an int as `:result`, so a
write has to say which member it writes. The first element of the pair
says it:

```clojure
(ffi/write p data [:result 0])            ; the :result member
(ffi/write p data [:whatever some-ptr])   ; the :whatever member
(ffi/write p data [:nope 0])              ; error: no such member
(ffi/write p data {:result 0})            ; error: a union value is a pair
```

A pair is the shape Clojure already uses for one-of-several with a tag:
`spec`'s `s/or` conforms to `[:tag value]`. A struct is a map, an array a
vector, a union a tagged pair, so each layout kind has a Clojure shape of
its own, and a union value cannot be mistaken for a struct's.

coffi needs a separate `:dispatch` function for this choice, because its
union value is a bare `0`, which does not say whether it is the int or the
pointer. Here the value carries the choice.

The tag flows one way. A write names the member; a read cannot, because a
C union stores no tag. This is a property of C unions, not of the pair:
coffi has the same asymmetry (`clone-segment` on read, `:dispatch` on
write), and so does every binding in the table below. The pair is the
form a tagged read would return if one is ever added, so the two sides
would then round-trip.

### By value: refused

A bare union as an argument or return type in a `cfn` signature is refused
on both hosts, with a message that says to pass a pointer. A union inside a
struct is read and written from memory in the normal way; a struct that
holds a union is likewise refused by value in a signature.

The reason is the native image. libffi has no union type. The usual
stand-in, a struct whose one element is the largest member, gets the
register classification wrong on the System V ABI whenever the members
differ in class (a `union { double d; long l; }` is classified from both
members, not from one). The FFM linker on the JVM has `unionLayout` and
classifies correctly, so the JVM could do it; a feature that works on one
host and misbehaves on the other is worse than one that is refused on both.
cffi refuses a union by value for the same reason (`using.rst:444`). None
of the surveyed cases needs it. The restriction can be lifted later without
changing anything else.

## The options weighed

Three shapes were possible for what `read` returns. Each was weighed on
correctness, performance and how well the API can grow.

**Option 1, the bytes.** `read` returns a pointer to the union's bytes; the
caller reads the member it knows applies. This is the decision.

- Correctness: nothing is decoded that cannot be vouched for. Works for all
  four cases in the table, including the two where the tag is outside the
  union or absent.
- Performance: one slice. No new memory-access sites in the image.
- Growth: it is the primitive the other options need anyway; a tagged read
  can be added on top later without changing what an untagged union
  returns.

**Option 2, every member decoded into a map.** Rejected on correctness. A
member that is not live holds bytes that mean something else: a `:pointer`
member becomes an address that crashes on the next read, and a `:string`
member makes `read` itself walk random bytes as a C string. For `SDL_Event`
it also decodes 29 members per event to use one.

**Option 3, a tag function in the layout.**

```clojure
[:union [[:key key-event] ...] :tag (fn [u] ...)]
```

Works only where the tag is inside the union: `SDL_Event` and
`duckdb_string_t`. For `CURLMsg` and `epoll_data_t` the function has
nothing to look at, so option 1 has to exist regardless, and option 3 can
only be sugar on top of it. It also puts a function into a layout, which
until now is plain data: printable, comparable, and free of any knowledge
of the program's own values. Deferred; can be added as an opt-in.

## What other FFIs do

Every FFI that has unions makes the same choice as option 1. Verified in
the source or by running it:

| FFI | union | reading a member | decodes all members? | tag in the type? |
|---|---|---|---|---|
| coffi (`mem.clj:1524-1565`) | `MemoryLayout/unionLayout` | `deserialize-from` returns a cloned raw segment; the caller deserializes the member | no | no; a `:dispatch` function on write only |
| dtype-next 8.041 | none | – | – | – |
| Python ctypes (run) | `ctypes.Union`, a view | `d.fd`, `d.u64`, `d.ptr` read the same bytes on access; `repr` shows no values | no | no |
| Python cffi (`using.rst:168`) | a cdata object over the memory | attribute access reads the bytes | no | no |
| Ruby FFI (`union.rb`, `Struct.c:357`) | `Union < Struct`, every offset 0 | `[]` calls `memoryOp->get(pointer, offset)` on access | no | no |
| Haskell (`Foreign/Storable.hs`) | none; `Storable` is `peekByteOff`/`pokeByteOff` | `peek` the member at its offset | no | no |

coffi's `:dispatch` exists because its union *value* carries no envelope:
`(serialize-into 42 union-type seg arena)` cannot tell which member `42`
is, so a function has to look at the value. The one-key map is the same
dispatch expressed as data, and it makes the ambiguous case (`{:fd 42 :u32
42}`) an error instead of a guess.

## Consequences

- A struct that holds a union no longer reads as pure data: that field is a
  pointer, so `=` on two such reads compares address and size, not bytes.
  ctypes and coffi have the same property. It is inherent to option 1 and
  was judged less bad than decoding members that may be garbage.
- The layout kinds become `#{:struct :array :union}`. The clj-kondo hook
  and the sync test that keeps it equal to the library follow.
- `babashka.duckdb` can read `duckdb_string_t` directly from a vector's
  data, which removes a `duckdb_value_varchar` call and a `duckdb_free` per
  string cell.
- A union by value in a signature is an error. If a library needs it, the
  JVM path is one `unionLayout` away; the native path needs libffi to grow
  a union type or a per-ABI classification. Neither is planned.

## Alternatives not taken

- A one-key map for the write value, `{:result 0}`. It was the first
  draft. It carries a rule the shape does not show ("exactly one key"),
  it is a struct's shape, and a tagged read would have to return a map
  that reads as a struct. The pair wins on all three counts, and its
  encoder allocates nothing where `first` on a map allocates an entry.
- A union as an opaque byte vector, `[0 42 0 0 ...]`. Comparable with `=`,
  but reading a member then needs a write-back into memory first, which
  costs an allocation and hides the fact that the bytes have a type.
- A separate `read-union` function that takes the member name:
  `(read-union p layout :fd)`. It is `(ffi/read (ffi/read p layout) :int)`
  with one fewer step and one more name. Rejected for now; it is additive if
  wanted.
