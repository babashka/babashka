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

Pass this map to `cfn` to limit the search to that library and its
dependencies:

```clojure
(def zlib-version (ffi/cfn zlib "zlibVersion" [] :string))
```

Without a library map, `cfn` searches all loaded libraries and the default
system lookup. `find-symbol` follows the same rules.

A shared library exports functions and global variables by name. An exported
name is a symbol.

Use `find-symbol` to get a pointer to a symbol without a function binding:

```clojure
(ffi/find-symbol "zlibVersion")
;;=> a pointer
```

The result is a pointer. You can pass it to a C function that accepts a
function or data pointer, or to `cfn` to bind it.

If `find-symbol` cannot find the symbol, it returns `nil`.

Pass a library map to limit the search to that library and its dependencies:

```clojure
(ffi/find-symbol zlib "zlibVersion")
```

Without a library map, `find-symbol` searches all loaded libraries and then
the default system lookup.

A symbol from the selected library takes priority over symbols from its
dependencies. Thus, a bundled library supplies its own function.

The search can also find symbols from the dependencies. For example,
`(ffi/find-symbol zlib "strlen")` returns the address of the C library's
`strlen`.

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

`cfn` also accepts a function address instead of a name:

```clojure
(def c-abs (ffi/cfn (ffi/find-symbol "abs") [:int] :int))
(c-abs -42)
;;=> 42
```

Use this form for a function that has no exported name.

Function addresses can come from loaders, C functions, struct fields, or
`callback`.

Read a pointer field with `read` and `:pointer`. Then pass the result to
`cfn`.

`cfn` rejects address zero. A loader returns zero when it does not have the
requested function.

CAUTION: Make sure that the address points to a function with the declared
signature. An incorrect address or signature can stop the process.

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

The `:library` key in the attribute map selects the library for the binding:

```clojure
(def sqlite (delay (ffi/load-library (extract-bundled-library!))))

(defcfn sqlite3-open {:library sqlite}
  "sqlite3_open" [:string :pointer] :int)
```

If you ship a library with your application, use `:library`.

Without this key, the binding searches all loaded libraries and then the
system.

A system library with the same name can then supply the symbol. As a result,
the application can call a version that you did not select.

`:library` accepts one of these values:

- A library map
- A function that returns a library map
- A `delay`, `atom`, or var that holds a library map.

At the first call, the binding gets the library and resolves the symbol. The
binding keeps the function address.

A function or `delay` can refer to a library that loads later. Changes to the
library value after the first call do not change the binding.

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
| `:pointer` | A pointer, see [Use native memory](#use-native-memory). |
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

A pointer is a native `java.lang.foreign.MemorySegment` with a live scope
that the current thread may access. A heap segment does not have a C
address. A closed-arena pointer refers to released memory. A confined arena
belongs to one thread. The API rejects all three before it passes an address
to C.

Babashka does not expose the `MemorySegment` class to scripts because the class
increases the binary size.

Use `size`, `address`, `slice`, `reinterpret`, and `pointer?`.

CAUTION: Do not pass a confined segment from another thread. C can bypass the
thread-access restriction.

`alloc` returns a segment with a size. Access outside a nonzero segment throws
an `IndexOutOfBoundsException`.

C does not report the size of a returned pointer. Thus, the pointer has size
zero. Memory access functions reject these pointers.

Before you access the memory, specify its size with `reinterpret`:

```clojure
;; C returned p without a size. The struct has 16 bytes.
(ffi/read (ffi/reinterpret p 16) :int 8)
```

`alloc 0` and an end-of-block slice also have size zero. `ptr->string` rejects
size zero and reads other pointers within their size. Declare a C string return
type as `:string`.

Use `size` to get the segment size. Use `address` to convert a pointer to a
long. Use `segment` to convert a raw address to a pointer. Use `slice` to
select part of a segment. The `+` function does not support pointers.

```clojure
(ffi/size p)             ;;=> 16
(ffi/address p)          ;;=> 4438706736
(ffi/segment 4438706736) ;;=> a pointer of size 0
(ffi/segment addr 16)    ;;=> a pointer of size 16
(ffi/slice p 8)          ;;=> the rest of p from byte 8
(ffi/reinterpret p 64)   ;;=> p with size 64
```

A C pointer argument accepts a pointer or `nil`. A `nil` value is NULL.
Pointer arguments reject numbers. `ffi/null` is the NULL pointer:

```clojure
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

CAUTION: Do not use a pointer after `free`. This can corrupt memory or stop the
process.

`alloc` also accepts a type keyword instead of an integer byte count:

```clojure
(ffi/alloc :pointer)   ; 8 bytes
```

### Arenas

An arena owns its allocated memory. Closing the arena releases this memory.

Create an arena in `with-open`:

```clojure
(with-open [arena (ffi/confined-arena)]
  (let [p (ffi/alloc arena :int)
        q (ffi/alloc arena 256)]
    (ffi/write p :int 42)
    (ffi/read p :int)))
;;=> 42
```

The arena releases `p` and `q` when the body ends. It also releases them if the
body throws. After release, memory access throws an `IllegalStateException`.
C functions reject pointers from a closed arena.

CAUTION: Do not call `free` on memory that an arena allocated. This operation
can stop the process.

CAUTION: Do not close an arena while C uses its memory. C can access released
memory.

Arena memory uses the alignment of its allocation. A type uses its natural
alignment. An integer byte count uses alignment 16. Specify another alignment
when necessary:

```clojure
(ffi/alloc arena 4096 64)   ; 4096 bytes on a 64-byte boundary
```

Use `confined-arena` for memory that one thread uses. Other threads cannot
access its pointers. Use `shared-arena` for memory that multiple threads use.

CAUTION: Do not close a shared arena while another thread is in a C call
with its memory. The call continues on released memory. A pointer goes to C
as an address, so the arena does not know that the call is in progress.
Create both arena types in `with-open`.

The garbage collector releases an `auto-arena` after it becomes unreachable.
Keep the arena reachable while C uses its pointers.

A `global-arena` exists until the process stops. You cannot close an automatic
or global arena.

The functions return a `java.lang.foreign.Arena`, so the same code runs in
babashka and on the JVM.

`read` and `write` accept an optional byte offset:

```clojure
(ffi/write p :int 0 42)
(ffi/write p :double 8 1.5)

(ffi/read p :int 0)
(ffi/read p :double 8)
```

`read` supports each listed type except `:void`. `write` also excludes
`:string`. Write a string address as `:pointer`.

Use `read-bytes` and `write-bytes` to copy complete byte arrays between
native memory and the JVM. Both functions accept an optional byte offset:

```clojure
(ffi/write-bytes p (byte-array [1 2 3 4]))
(ffi/read-bytes p 4)
;;=> byte array [1 2 3 4]
```

Use `byte-buffer` to create a zero-copy `java.nio.ByteBuffer` view of native
memory:

```clojure
(ffi/byte-buffer p 4096)
```

The buffer and native memory share the same bytes.

CAUTION: Do not use the buffer after you release the native memory. An invalid
memory access can stop the process.

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
     (compare (ffi/read (ffi/reinterpret left-pointer 4) :int)
              (ffi/read (ffi/reinterpret right-pointer 4) :int)))
   [:pointer :pointer]
   :int))

(qsort values 5 4 comparator)
(ffi/free-callback comparator)
```

`callback` returns a function pointer. The pointer stays valid until you
call `free-callback`.

A `:pointer` callback argument comes from C and has size zero.

Before you read the memory, specify its size with `reinterpret`.

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

Struct values are not supported as direct arguments or return values, yet.

If a signature is not supported, use libffi or write a small C wrapper.
See [`examples/ffi/libffi.clj`](../examples/ffi/libffi.clj) for a libffi example.

## Examples

The [`examples/ffi`](../examples/ffi) directory contains complete examples
for SQLite, CPython, libffi, and raylib.
