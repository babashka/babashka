# babashka.ffi

The documentation for `babashka.ffi` lives with the library, in the
[babashka/ffi](https://github.com/babashka/ffi) repository:
[doc/ffi.md](https://github.com/babashka/ffi/blob/main/doc/ffi.md).

Babashka embeds that library as a submodule, so the namespace is built in and
`(require '[babashka.ffi :as ffi])` is all a script needs. The same library
runs on JVM Clojure, where it needs JDK 22 or newer.
