# ADR 0003: Pass C structs by value

## Status

Accepted 2026-08-26. Implemented on branch `ffi-structs` (PR 5 of the FFI
series). This decision builds on ADR 0002 and the libffi integration from
#2051.

## Context

Some C functions pass or return structs by value. Examples include `div`,
physics functions, and UI functions.

The native call signatures from ADR 0002 support primitive values only. Each
platform has different rules for passing structs. These rules can use
registers, memory, or a hidden return pointer. Libffi applies the correct rules
from a run-time description of the call.

This decision defines the layout syntax, the Clojure value format, and the
libffi call path.

## Decision

### Use named layouts

Use a vector with the layout kind first. A struct contains a vector of
`[name type]` field pairs:

```clojure
(def point [:struct [[:x :int] [:y :int]]])
(def rect  [:struct [[:lo point] [:hi point]]])

(ffi/sizeof point)   ;;=> 8
(ffi/alignof rect)   ;;=> 4
```

A field name is a keyword. A field type is a type keyword or another layout.
Thus, layouts can contain other layouts.

Babashka calculates the field offsets, padding, size, and alignment. At bind
time, it compares each struct size and alignment with the libffi result. A
difference causes an error.

`sizeof` and `alignof` accept type keywords and layouts.

The tag-first form permits more layout kinds later. For example, arrays or
unions can use a different tag. This decision does not define those kinds.

### Use maps for values

Use a map from field names to field values:

```clojure
(defcfn p2-add "p2_add" [point point] point)
(p2-add {:x 1 :y 2} {:x 10 :y 20})
;;=> {:x 11 :y 22}
```

Use nested maps for nested structs. A missing or unknown field causes an
error. Babashka does not support positional vector values.

The call copies values to and from native memory. This decision does not add
memory-backed struct views.

### Do not add conversion hooks

This API does not add hooks that convert structs to custom Clojure values.
Such hooks would add API surface and run user code during marshalling. A
wrapper function can convert a result when necessary:

```clojure
(defn body-position [id]
  (let [{:keys [x y z]} (c-body-position id)]
    (vec3 x y z)))
```

Conversion hooks can be added later if there is sufficient demand.

### Use libffi for struct calls

Only a signature that contains a struct uses libffi. Other signatures use the
call paths from ADR 0002.

At bind time, babashka creates the field encoders, field decoders, `ffi_type`
tree, and call interface. Each call uses a confined arena for temporary
memory.

This implementation does not support structs in variadic signatures.

Native binaries use their linked libffi. Builds made with
`BABASHKA_LIBFFI=none` and the musl static binary do not support struct calls.
On the JVM, babashka uses the system libffi. A struct binding causes an error
if libffi is not available.

The bound function has `:babashka.ffi/backend :libffi` in its metadata.

## Consequences

- The layout contains names and the value is a map. This makes field order
  explicit and lets babashka reject field-name errors.
- Struct calls are slower than primitive calls because they use libffi and
  temporary memory.
- Struct callbacks and memory-backed struct views remain out of scope.
