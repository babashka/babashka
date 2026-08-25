# babashka.ffi

`babashka.ffi` calls functions in native shared libraries.

The API is experimental.

CAUTION: Use only correct signatures and valid pointers. An incorrect value
can stop the process.

## Quick start

Load a library and bind a function:

```clojure
(require '[babashka.ffi :as ffi :refer [defcfn]])

(def zlib (ffi/load-system-library "z"))
(def zlib-version (ffi/cfn zlib "zlibVersion" [] :string))

(zlib-version)
;;=> "1.3.1"
```

`load-system-library` adds the platform file name. For example, `"z"`
becomes `libz.dylib`, `libz.so`, or `z.dll`.

## Load a library

Use `load-system-library` for a short library name:

```clojure
(ffi/load-system-library "z")
```

On Linux, this function also searches for versioned names such as
`libz.so.1`, in the `LD_LIBRARY_PATH` directories and the directories
listed below.

Use `load-library` for an exact file name or path:

```clojure
(ffi/load-library "/exact/path/libfoo.so")
```

`load-library` does not change the candidate names.

Pass a vector to try multiple candidates in order:

```clojure
(ffi/load-library ["libfoo.so.3" "libfoo.so"])
```

Pass a map to select candidates for each operating system:

```clojure
(ffi/load-library
 {:mac ["/opt/homebrew/opt/openssl@3/lib/libcrypto.3.dylib"
        "/usr/local/opt/openssl@3/lib/libcrypto.3.dylib"]
  :linux "libcrypto.so.3"
  :windows "libcrypto-3-x64.dll"})
```

The supported keys are `:mac`, `:linux`, and `:windows`. You can use
`:darwin` instead of `:mac`.

Both load functions first use the system library search. For a bare name,
they then search these directories.

macOS:

- `/opt/homebrew/lib`
- `/usr/local/lib`
- `/opt/local/lib`
- `/usr/lib`

Linux:

- the directories in `LD_LIBRARY_PATH`
- `/usr/local/lib`
- `/usr/lib64`
- `/usr/lib`
- `/usr/lib/x86_64-linux-gnu` or `/usr/lib/aarch64-linux-gnu`, matching the
  current architecture
- `/lib64`
- `/lib`
- `/lib/x86_64-linux-gnu` or `/lib/aarch64-linux-gnu`, for systems where
  `/lib` is not merged into `/usr/lib`

Windows doesn't have additional search directories.

On FreeBSD, babashka runs as a Linux binary through the
[Linuxulator](https://docs.freebsd.org/en/books/handbook/linuxemu/). The
Linuxulator translates `/usr/lib64` and `/lib64` to
`/compat/linux/usr/lib64` and `/compat/linux/lib64`, so libraries installed
there are found through the paths above.

Both functions return a library map. The `:path` value contains the loaded
candidate:

```clojure
(def zlib (ffi/load-system-library "z"))
(:path zlib)
;;=> "libz.dylib"
```

Pass this map to `cfn` to limit the search to that library:

```clojure
(def zlib-version (ffi/cfn zlib "zlibVersion" [] :string))
```

Without a library map, `cfn` searches all loaded libraries and the default
system lookup.

A shared library exports functions and global variables by name. An exported
name is a symbol.

Use `find-symbol` to get a pointer to a symbol without a function binding:

```clojure
(ffi/find-symbol "zlibVersion")
;;=> 4438706736
```

The result is a native address in a Clojure long. You can pass this value to
a C function that accepts a function or data pointer.

If `find-symbol` cannot find the symbol, it returns `nil`.

Pass a library map to limit the search to that library, as `cfn` does:

```clojure
(ffi/find-symbol zlib "zlibVersion")
```

Without a library map, `find-symbol` searches all loaded libraries and then
the default system lookup.

A library map limits the search to that library and the libraries that it
links. A symbol that the library defines resolves to the definition in that
library, so a bundled library gives you its own function and not the copy
that the system installs. A symbol that the library does not define can
still come from one of its dependencies: `(ffi/find-symbol zlib "strlen")`
returns the address of the C library's `strlen`.

## Bind a function

Use `cfn` to create a Clojure function:

```clojure
(def z-error (ffi/cfn zlib "zError" [:int] :string))
(z-error -3)
;;=> "data error"
```

The arguments to `cfn` are the C symbol, argument types, and return type.
The symbol lookup occurs on the first call.

### Bind an address

`cfn` also accepts the address of a function instead of a name:

```clojure
(def c-abs (ffi/cfn (ffi/find-symbol "abs") [:int] :int))
(c-abs -42)
;;=> 42
```

Use this for a function that has no name to look up. A loader such as
`glXGetProcAddress` returns the address of a function, a C function returns
a function pointer, a struct holds one in a field, and `callback` returns
the address of a Clojure function. Read a pointer field with `read` and
`:pointer`, then bind the address that you read.

`cfn` rejects the null address, which is what a loader returns for a
function that it does not have.

CAUTION: An address must point to a function with the signature that you
declare. An incorrect address or signature can stop the process.

Use `defcfn` to define and bind a function:

```clojure
(defcfn zlib-version "zlibVersion" [] :string)
(zlib-version)
;;=> "1.3.1"
```

You can add a docstring and an attribute map before the C symbol:

```clojure
(defcfn zlib-version
  "Returns the zlib version."
  {:added "1.0"}
  "zlibVersion" [] :string)
```

### Types

Use these type keywords in function signatures:

| Type | Meaning |
|---|---|
| `:void` | No return value. Do not use it as an argument type. |
| `:int`, `:int32` | Signed 32-bit integer. |
| `:uint`, `:uint32` | Unsigned 32-bit integer. |
| `:long`, `:int64` | Signed 64-bit integer. |
| `:ulong`, `:uint64` | Unsigned 64-bit integer bits in a Clojure long. |
| `:int16` | Signed 16-bit integer. |
| `:uint16` | Unsigned 16-bit integer. |
| `:int8`, `:byte`, `:char` | Signed 8-bit integer. |
| `:uint8` | Unsigned 8-bit integer. |
| `:size_t` | Unsigned 64-bit size. |
| `:ssize_t` | Signed 64-bit size. |
| `:float` | 32-bit floating-point number. |
| `:double` | 64-bit floating-point number. |
| `:bool` | One-byte C boolean. |
| `:pointer` | Native address in a Clojure long. |
| `:string` | Pointer to a NUL-terminated UTF-8 string. |

`:long` and `:ulong` are always 64-bit types. A C `long` is 32 bits on
Windows. Use the type that matches the C declaration.

A `:bool` argument uses Clojure truthiness. A `:bool` return value is
`true` or `false`.

```clojure
(defcfn window-should-close? "WindowShouldClose" [] :bool)
(when-not (window-should-close?) ...)
```

A `:uint8` return value is a number. In Clojure, both `0` and `1` are
truthy.

A `:string` argument uses temporary memory. C must not keep this pointer
after the function returns.

If C keeps the pointer, allocate the string with `string->ptr`. Free this
pointer after C no longer uses it.

A `:string` return value reads the pointer as UTF-8. A NULL return value
becomes `nil`.

### Pass a struct by value

Write a struct that C passes or returns by value as `{:struct [layouts]}`,
where a layout is a type keyword or another struct layout. A struct value
is a vector of its fields:

```clojure
(defcfn c-div "div" [:int :int] {:struct [:int :int]})
(c-div 7 2)
;; => [3 1]
```

Struct layouts nest, and so do their values:

```clojure
(def point {:struct [:int :int]})
(def rect {:struct [point point]})
(defcfn rect-grow "rect_grow" [rect :int] rect)
(rect-grow [[1 1] [5 5]] 2)
;; => [[-1 -1] [7 7]]
```

`sizeof` takes a struct layout. It returns the size that a C compiler gives
the struct, padding included:

```clojure
(ffi/sizeof {:struct [:char :double]})
;; => 16
```

These calls go through libffi, which places the arguments from a
description of the call. A struct call costs about 1us, against 120ns for a
call that takes only primitives. Only a signature that has a struct in it
pays this.

A variadic signature cannot pass a struct by value.

Struct calls need libffi. A released babashka binary has it linked in. A
binary built from source needs `BABASHKA_LIBFFI`, see `script/setup-libffi`.
On the JVM, babashka loads the system libffi. Where there is no libffi, a
struct binding throws.

## Call a variadic function

Put `:&` after the fixed argument types:

```clojure
(defcfn c-open "open" [:string :int :&] :int)

(c-open path O_RDONLY)
(c-open path flags 0644)
```

The values after the fixed arguments determine the variadic types:

| Clojure value | Variadic type |
|---|---|
| Integer, pointer, boolean, or `nil` | 64-bit integer |
| Floating-point number or ratio | `double` |
| String | NUL-terminated C string |

The fixed arguments and variadic values must match the C function contract.
For example, a `printf` format must match its values.

```clojure
(defcfn c-printf "printf" [:string :&] :int)
(c-printf "%s: %.0f\n" "count" 42.0)
```

## Use native memory

Pointers are native addresses stored in Clojure longs. `ffi/null` is the
NULL address.

```clojure
ffi/null
;;=> 0

(ffi/null? ffi/null)
;;=> true
```

Use `alloc` to allocate zeroed memory. Always release this memory with
`free`:

```clojure
(let [p (ffi/alloc 16)]
  (try
    (ffi/write p :int 42)
    (ffi/read p :int)
    (finally
      (ffi/free p))))
;;=> 42
```

`read` and `write` accept an optional byte offset:

```clojure
(ffi/write p :int 0 42)
(ffi/write p :double 8 1.5)

(ffi/read p :int 0)
(ffi/read p :double 8)
```

`read` supports each listed type except `:void`. `write` also excludes
`:string`. Write a string address as `:pointer`.

Use `read-bytes` and `write-bytes` to copy whole byte arrays between
native memory and the JVM. Both take an optional byte offset:

```clojure
(ffi/write-bytes p (byte-array [1 2 3 4]))
(ffi/read-bytes p 4)
;;=> byte array [1 2 3 4]
```

Use `byte-buffer` for a zero-copy `java.nio.ByteBuffer` view over native
memory, for example to read a file straight into it with a FileChannel:

```clojure
(ffi/byte-buffer p 4096)
```

The view is only valid while the memory is.

Use `sizeof` to get the size of a type:

```clojure
(ffi/sizeof :pointer)
;;=> 8
```

Use `string->ptr` to allocate a C string. Release the result with `free`:

```clojure
(let [p (ffi/string->ptr "hello")]
  (try
    (ffi/ptr->string p)
    (finally
      (ffi/free p))))
;;=> "hello"
```

`ptr->string` reads a string at the specified address. It returns `nil` for
the NULL address.

If memory contains a string pointer, use `read` with `:string`:

```clojure
(ffi/read pointer-slot :string)
```

This operation first reads the pointer from `pointer-slot`. Then it reads
the string at that pointer.

CAUTION: Use only valid addresses and offsets. An invalid memory access can
stop the process.

### Out parameters

Allocate memory for a C out parameter. Then pass its address to the C
function:

```clojure
(defcfn sqlite3-open "sqlite3_open" [:string :pointer] :int)

(let [database-pointer (ffi/alloc (ffi/sizeof :pointer))]
  (try
    (sqlite3-open "example.db" database-pointer)
    (ffi/read database-pointer :pointer)
    (finally
      (ffi/free database-pointer))))
```

The returned database pointer belongs to SQLite. Close it with the related
SQLite function.

## Create a callback

Use `callback` to pass a Clojure function to C:

```clojure
(def comparator
  (ffi/callback
   (fn [left-pointer right-pointer]
     (compare (ffi/read left-pointer :int)
              (ffi/read right-pointer :int)))
   [:pointer :pointer]
   :int))

(qsort values 5 4 comparator)
(ffi/free-callback comparator)
```

`callback` returns a function pointer. The pointer stays valid until you
call `free-callback`.

If C keeps the callback, keep its pointer. Unregister the callback before
you call `free-callback`.

C can call a callback from a native thread. A `:bool` callback argument
becomes `true` or `false`.

CAUTION: Do not let a callback throw an exception. Catch exceptions inside
the callback, or the process can stop.

## Signature limits

Babashka includes a fixed set of native call signatures. If a signature is
unsupported, `cfn` or `callback` throws an exception.

Fixed functions have these limits:

- A function can have up to 6 arguments.
- Up to 3 arguments can use `:float` or `:double` in any combination.
- If all floating-point arguments use the same type, a function can have 4 of them.
- A function with only integer or pointer arguments can have up to 10 arguments.
- A function that returns `:float` can have up to 4 arguments.

Variadic functions have these limits:

- A call can have up to 5 arguments in total.
- A signature can have up to 3 fixed arguments.
- A call can have up to 2 `:double` arguments.
- A return type can be `:void`, an integer type, or a pointer type.

Callbacks have these limits:

- A callback can have up to 4 arguments.
- A callback can have up to 2 `:double` arguments.
- A callback cannot use `:float`.
- A return type can be `:void`, an integer type, or `:double`.

Argument order does not change these limits.

These limits cover the primitive types. A struct that C passes or returns
by value has none of them, because such a call goes through libffi. See
[Pass a struct by value](#pass-a-struct-by-value).

If a signature is not supported, use libffi or write a small C wrapper.
See [`examples/ffi/libffi.clj`](../examples/ffi/libffi.clj) for a libffi example.

## Examples

The [`examples/ffi`](../examples/ffi) directory contains complete examples
for SQLite, CPython, libffi, and raylib.
