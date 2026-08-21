# babashka.ffi

Experimental. Call C libraries from babashka: load a shared library, bind
functions with explicit types, marshal memory by hand.

```clojure
(require '[babashka.ffi :as ffi :refer [defcfn]])

(ffi/load-system-library "sqlite3")
(defcfn sqlite3-open "sqlite3_open" [:string :pointer] :int)
(defcfn sqlite3-libversion "sqlite3_libversion" [] :string)

(sqlite3-libversion)
;;=> "3.43.2"
```

## Loading libraries

```clojure
(ffi/load-system-library "z")
```

Loads a library by short name: `libz.dylib` on macOS, `z.dll` on Windows,
`libz.so` on Linux with a fallback glob over versioned sonames
(`libz.so.1`). Searches the system's dlopen path, then common install
directories (Homebrew, MacPorts, `/usr/local/lib`, the multiarch dirs) and
`BABASHKA_FFI_LIBRARY_PATH` (colon-separated).

```clojure
(ffi/load-library "/exact/path/libfoo.so")
(ffi/load-library
 {:mac ["/opt/homebrew/opt/openssl@3/lib/libcrypto.3.dylib"
        "/usr/local/opt/openssl@3/lib/libcrypto.3.dylib"]
  :linux "libcrypto.so.3"})
```

`load-library` is the exact-name form: a path, or a map from OS keyword
(`:mac` `:linux` `:windows`, `:darwin` works as `:mac`) to a path or a
vector of candidates tried in order. It never rewrites names. Bare names
get the same directory search as above.

```clojure
(ffi/find-symbol "strlen")
;;=> 4438706736
```

Probes for a symbol in the loaded libraries and libc. Returns the address,
or nil.

## Binding functions

```clojure
(def strlen (ffi/cfn "strlen" [:string] :size_t))
(defcfn c-abs "abs" [:int] :int)
```

`cfn` binds C function by symbol name, argument types, return type.
`defcfn` is `def` + `cfn`, with an optional docstring and attribute map
before the C symbol:

```clojure
(defcfn strlen
  "Length of a C string in bytes."
  {:added "1.0"}
  "strlen" [:string] :size_t)
```

Binding is lazy: the symbol resolves on first call. An optional first argument takes a specific library (the return value
of `load-library`); without it, symbols resolve in all loaded libraries and
then libc.

Types: `:void` `:int` `:uint` `:long` `:ulong` `:int8` `:uint8` `:int16`
`:uint16` `:int32` `:uint32` `:int64` `:uint64` `:size_t` `:ssize_t`
`:char` `:byte` `:bool` `:pointer` `:string` `:double` `:float`.

`:bool` is C's one-byte bool. It takes any Clojure value as an argument and
returns `true` or `false`, so a predicate reads the way it should:

```clojure
(defcfn window-should-close? "WindowShouldClose" [] :bool)
(when-not (window-should-close?) ...)
```

Binding such a function as `:uint8` instead returns `0`, which is truthy in
Clojure.

Pointers are longs. A `:string` argument is copied to a NUL-terminated C
string for the call; a `:string` return reads one back as UTF-8.

Variadic C functions take a trailing `:&`: types before it are the fixed
parameters, the tail is inferred per call from the values (integers and
pointers as 64-bit ints, floats as double per C promotion, strings as C
strings). One binding covers every tail shape, including the empty one.

```clojure
(defcfn c-open "open" [:string :int :&] :int)
(c-open path O_RDONLY)        ; no mode
(c-open path flags 0644)      ; with mode
```

Whether the callee reads a tail slot as the type you passed is the caller's
contract, as in C: `(printf "%f" 3)` passes an integer where the format
reads a double.

## Memory

```clojure
(ffi/alloc 16)              ; pointer to 16 zeroed bytes
(ffi/free p)
(ffi/read p :int)           ; typed read, optional byte offset
(ffi/read p :double 8)
(ffi/write p :int 42)       ; typed write, optional byte offset
(ffi/write p :int 8 42)
(ffi/sizeof :pointer)       ;=> 8
(ffi/string->ptr "hi")      ; C string in fresh memory, free it yourself
(ffi/ptr->string p)
ffi/null                    ; 0
(ffi/null?* p)
```

The out-parameter pattern:

```clojure
(let [pp (ffi/alloc (ffi/sizeof :pointer))]
  (try (sqlite3-open "x.db" pp)
       (ffi/read pp :pointer)
       (finally (ffi/free pp))))
```

## Callbacks

```clojure
(def cmp (ffi/callback (fn [pa pb]
                         (compare (ffi/read pa :int) (ffi/read pb :int)))
                       [:pointer :pointer] :int))
(qsort arr 5 4 cmp)
(ffi/free-callback cmp)
```

`callback` wraps a Clojure fn as a C function pointer. The callback stays
alive until `free-callback`; C must not call it afterwards. For a callback
C retains, such as a log handler, keep the pointer and only free it after
unregistering. One callback can be passed to any number of calls. Callbacks
may be invoked from threads C created.

## Limits

A native image cannot build a call at run time, so bb ships a fixed set of
call shapes and maps each signature onto one of them. This is what a
signature must fit:

- Up to 6 arguments, of which at most 6 are pointer or integer types.
- The floating-point arguments may be any mix of `:double` and `:float` up
  to three of them, or four when they are all the same type.
- A signature of only pointer and integer types may have up to 10 arguments.
- A `:float` return needs 4 arguments or fewer.
- Variadic calls: up to 5 arguments, of which at most 3 fixed and at most 2
  `:double`.
- Callbacks: up to 4 arguments, at most 2 `:double`, no `:float`, and a
  `:void`, integer, or `:double` return.

Argument order does not matter, only how many of each kind there are. So
`[:double :int]` and `[:int :double]` use the same shape.

The limits come from measurement, not from the ABI. About 350 bindings from
raylib, sqlite, duckdb, CPython, OpenSSL and libffi need 37 shapes between
them; bb registers 286, and each shape costs about 1.7 kB of binary. Report
a signature that does not fit and it can usually be added.

Struct-by-value arguments and returns are not supported yet.

An unsupported signature fails when the function is bound, with the limits
in the message. Workaround until a limit is lifted: bind libffi through
babashka.ffi itself and call the function with `ffi_call` - `ffi-libffi.clj`
in the babashka repo shows the pattern, including struct-by-value returns.
Signatures reported in issues can usually be added.

`(meta (ffi/cfn ...))` contains `:babashka.ffi/backend`: `:trampoline` is
the compiled fast path, `:ffm` the fallback.
