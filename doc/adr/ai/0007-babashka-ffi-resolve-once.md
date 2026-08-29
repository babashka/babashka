# ADR 0007: Resolve once, then run

## Status

Accepted 2026-08-29. A principle, not a feature: it names a rule the API
already follows in `cfn`, `callback`, `field-reader` and `field-writer`
(ADR 0006), and binds every later addition to it.

## Context

Much of what `babashka.ffi` does per call is not the memory access or the
C call. It is turning a description into the facts the access needs: a
symbol into an address, a signature into a call shape, a layout and a path
into an offset and a codec, a variadic argument list into a tail shape.
Each of those is a lookup keyed by a runtime value, and a lookup keyed by
a runtime value cannot be removed by a JIT or by an interpreter's
call-site cache, because the key is data and the table lives behind a
mutable reference. It can only be removed by the program doing it once.

Measured on the JVM while deciding ADR 0006 (best of five runs of two
million), for a 33-slot struct and a two-int struct:

    read-field :parent, per call, resolving each time      26-31 ns
    read p (place bone :parent), the place made once         3.5 ns
    (read p point), per call                                 107 ns
    read p (place point), the whole layout, made once         16 ns
    read :int 32, the offset by hand                        1.7 ns  (the JIT folds a constant)

In the native image nothing folds, and the hoisted accessor costs exactly
the hand read (102 ns against 101) at any path depth, where the per-call
form pays about 55 ns more and the whole-struct read 1466 ns.

A per-call form and a hoisted form of the same operation are not two
conveniences. Measured, a `let` that makes the accessor and uses it once
costs the same as the per-call form; every further use is nearly free. So
the per-call form has no case in which it wins, and it costs the API a
second name and a second habit.

## Decision

**A function with a resolve step is made once and kept, and has no
per-call twin.** Resolve steps are: finding a symbol, building a call for
a signature, walking a layout to a place, choosing a codec, fixing a
variadic tail shape. The function that does one returns a function; the
returned function only accesses or calls.

This is the shape of `cfn` (symbol and signature, once), `callback`
(stub, once), and `place` (path and codec, once; the whole layout without
a path), which `read` and `write` then take where they take a type. The one-off use is a `let`,
which costs the per-call price once and nothing after.

`read` and `write` stay as per-call primitives, and are the exception the
rule defines: for a scalar type there is nothing to resolve (a keyword is
a `case` dispatch), and for a whole layout they are the one-off operation
itself, fill a struct and hand it to C. When a whole layout is read in a
loop, `(place layout)` is the hoisted form.

Where a per-call spelling is wanted later, it is a macro that hoists at
expansion time, the way Specter's `select` caches a compiled path at the
call site, never a function that resolves at run time. Additive, and not
planned.

## Consequences

- New features are designed as "resolve, then return a function". A
  proposal that resolves on every call must say why the rule does not
  apply.
- Two follow-ups already fall under it: the empty path as the root of a
  layout (babashka/ffi `root-path`), and a declared variadic tail shape,
  `(cfn "printf" [:string :& :int :string] :int)`, resolved at bind time
  instead of inferred from the values on every call.
- The lead applies outside this namespace too. Anything in babashka that
  interprets a description per call, a CLI spec, a glob pattern, a
  template, is a candidate for the same split; each is its own decision.
