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
`defcfn` is `def` + `cfn`. Binding is lazy: the symbol resolves on first
call. An optional first argument takes a specific library (the return value
of `load-library`); without it, symbols resolve in all loaded libraries and
then libc.

Types: `:void` `:int` `:uint` `:long` `:ulong` `:int8` `:uint8` `:int16`
`:uint16` `:int32` `:uint32` `:int64` `:uint64` `:size_t` `:ssize_t`
`:char` `:byte` `:pointer` `:string` `:double` `:float`.

Pointers are longs. A `:string` argument is copied to a NUL-terminated C
string for the call; a `:string` return reads one back as UTF-8.

Variadic C functions need a `:varargs` marker: types before it are the
fixed parameters, types after it the variadic arguments the binding passes.

```clojure
(defcfn c-fcntl "fcntl" [:int :int :varargs :int] :int)
```

## Memory

```clojure
(ffi/alloc 16)              ; pointer to 16 zeroed bytes
(ffi/free p)
(ffi/read p :int)           ; typed read, optional byte offset
(ffi/read p :double 8)
(ffi/write p :int 0 42)     ; typed write at byte offset
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
alive until `free-callback`; C must not call it afterwards. Callbacks may
be invoked from threads C created.

## Limits

Up to 7 arguments, of which at most 6 pointer/integer, at most 6 `:double`,
and at most 4 floating args when any is `:float`. A `:float` return needs 4
args or fewer. Pure pointer/integer signatures may have up to 10 arguments.
Variadic: up to 5 arguments, at most 3 fixed, at most 2 `:double`, no
`:float`. Callbacks: up to 4 arguments, at most 2 `:double`, no `:float`,
and a `:void`, integer, or `:double` return. Argument order does not
matter, only the counts.

Struct-by-value arguments and returns are not supported yet.

An unsupported signature fails when the function is bound, with the limits
in the message. Workaround until a limit is lifted: bind libffi through
babashka.ffi itself and call the function with `ffi_call` - `ffi-libffi.clj`
in the babashka repo shows the pattern, including struct-by-value returns.
Signatures reported in issues can usually be added.

`(meta (ffi/cfn ...))` contains `:babashka.ffi/backend`: `:trampoline` is
the compiled fast path, `:ffm` the fallback.
