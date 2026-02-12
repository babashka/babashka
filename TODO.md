# TODO

## reify-fn: crash on unrecognized interface

`babashka.impl.reify/reify-fn` uses a `case` on the interface class name
(`gen-reify-combos` macro). When a user reifies an interface that isn't in the
pre-compiled list, the `case` throws an unhelpful `IllegalArgumentException`
instead of a clear error message explaining which interfaces are supported.

Consider adding a `:default` branch to the `case` with a user-friendly error,
or adopting a pluggable approach similar to `:deftype-fn` / `:reify-fn` in SCI.

See: `src/babashka/impl/reify.clj`
