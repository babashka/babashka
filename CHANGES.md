# Changes

## Breaking changes

## v0.0.44
- #173: BREAKING: rename `*in*` to `<input>` (in the `user` namespace). The name
  was a poor choice for two reasons. It shadowed `clojure.core/*in*`. Also, the
  value was not a dynamic var, but the earmuffs suggested otherwise.

## v0.0.43
- #160: Add support for `java.lang.ProcessBuilder`. See docs. This replaces the
  `conch` namespace.
