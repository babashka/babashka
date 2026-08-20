# ADR 0002: babashka.ffi over pre-registered canonical descriptors

## Status

Spike on branch `worktree-ffi-spike`, working on macOS aarch64. Not merged.

## Context

GraalVM native image supports the FFM API (Panama), but every
`FunctionDescriptor` used for a downcall or upcall must be registered at image
build time. bb ships prebuilt binaries, so users can never register new
descriptors. A naive per-signature model would limit users to an enumerated
list of C functions.

Chez-based systems (jolt) compile call trampolines at runtime and have no such
bound. bb cannot do runtime codegen, so the design goal is: cover almost every
real C signature with a bounded, pre-registered descriptor family, and map
arbitrary user signatures onto that family at call time.

`jolt.ffi` provided the API model (explicit types, manual memory, `:varargs`
marker). coffi provided the JVM prior art (data-driven FFM). The measured cost
basis for all sizing decisions: one registered descriptor adds ~1.3KB to the
image.

## Trick 1: integer widening

All pointer and integer types (`:int :uint :pointer :string :size_t` ...) share
one 64-bit carrier (`JAVA_LONG`). Sound because the SysV x86-64, AAPCS64, and
Win64 ABIs pass integer arguments in 64-bit registers and the callee reads the
low bits. Returns declared `JAVA_LONG` carry garbage in the high bits when the
C function returns a narrower type, so the wrapper masks and sign- or
zero-extends per the declared type (`narrow-ret`). `:float` cannot widen to
`:double`: float and double args use different registers or widths, so float
keeps exact layouts, bounded separately.

## Trick 2: sort by register class, GP counts x FP sequences

On SysV x86-64 and AArch64, integer and floating-point arguments are assigned
registers from two independent sequences (GP and FP). `f(long, double, long)`
and `f(long, long, double)` use identical registers (x0, x1, d0). Therefore
argument order BETWEEN those classes does not affect the calling convention
as long as nothing spills to the stack.

WITHIN the FP class the assumption does NOT hold: float and double share one
register sequence, so their relative order is part of the ABI. This was
found by the generated test library (mix_jfd returned garbage) after
initially sorting double before float. The correct canonical form: integer
carriers first, floating args after in DECLARED order (stable sort, equal
rank for :double and :float). Registered shapes are therefore a GP count
times an FP sequence, not three counts. Callbacks apply the inverse
permutation inside the stub wrapper.

This still collapses the family from full orderings (thousands) to hundreds
while widening coverage: doubles and floats mix at any position within the
bounds.

Soundness bounds, encoded in the generator:

- at most 6 integer args (x86-64 has 6 integer argument registers, then stack,
  where order matters)
- float counts within the 8 FP registers
- variadic calls excluded (stack-positional on macOS aarch64)
- not valid on Windows x64 (positional register assignment) - a Windows build
  needs an order-preserving family

## Trick 3: varargs via marker plus firstVariadicArg

`:varargs` inside the argtype vector (jolt's syntax): types before it are the
fixed parameters, after it the concrete variadic arguments the binding passes.
The marker index becomes FFM's `Linker.Option.firstVariadicArg`. Variadic
shapes are registered as a separate ordered sub-family.

coffi's `vacfn-factory` concatenates types into a fixed descriptor without the
option. That silently corrupts stack-passed variadic arguments on macOS
aarch64. Do not copy it. `:float` after the marker is rejected (C promotes
variadic floats to double).

## Trick 4: callbacks via IFn method handles

`ffi/callback` binds `clojure.lang.IFn.invoke` through a MethodHandle
(`findVirtual` + `bindTo` + `asType`) and wraps it in an upcall stub. In a
native image the upcall MH falls back to reflective invocation, so
`IFn.invoke` arities 0..8 need reflection registration. Stubs live in the
global arena for the process lifetime.

## Trick 5: compiled call trampolines via the SVM C interface

FFM downcall handles are MethodHandle trees. HotSpot JIT-compiles them
(123ns/call); a native image cannot generate code at runtime and interprets
the tree on every call (~3.4us, measured in pure Java with invokeExact - no
Clojure overhead involved). The interpretation cannot be avoided from the
caller side: Linker.Option.critical changes nothing, and a build-time-constant
address-less handle fails analysis ("should not reach here: linkToNative").

The fix bypasses FFM for non-variadic downcalls entirely:
`@InvokeCFunctionPointer` on a `CFunctionPointer` subinterface -
SubstrateVM's own C interface (org.graalvm.nativeimage.c.function, public
API since 19.0, nothing deprecated as of 25) - compiles to a direct native
call: word-typed pointer (no object, no boxing at the call), register loads
per the C ABI fixed at image build time from the method signature, and the
Java-to-native thread-state transition (so a blocking call never stalls the
GC - jolt's __collect_safe semantics by default). Measured 2.3ns/call.

The signature must be a build-time constant, so the generator emits one
interface + static method per canonical (shape x return) pair - 299 total,
~1.2MB - and a Clojure map from shape key ("J_JJD") to a builder fn. cfn
looks up the sorted shape key; a hit dispatches through the trampoline, a
miss falls back to FFM. The canonicalization tricks (widening, sorting) are
what make 299 enough. End-to-end native call cost: 63ns (was 4777ns); the
1500-point rlgl demo went from ~3fps to jolt-parity (85 vs ~100fps at a
120fps target, remaining gap is SCI interpreting the demo loop).

FFM remains for: varargs (a fixed-convention pointer call reintroduces the
arm64 variadic trap), upcalls/callbacks, the JVM path (word types do not
exist on HotSpot), and Windows mixed-order signatures (no sorting there, so
the sorted key misses; the ordered descriptor family stays registered on
Windows only - elsewhere non-variadic descriptors are no longer emitted).

This combination - per-shape compiled SVM trampolines under a data-driven
FFI with count-shape canonicalization - is not found in coffi, jolt, or
dtype-next; it is specific to the native-image constraint set and appears
to be novel.

## Numbers

bb baseline 73,282,320 bytes (worktree build, GraalVM 25.0.4, macOS aarch64).

| family                                   | stubs | overhead |
|------------------------------------------|-------|----------|
| full orderings, arity <= 8               | ~3500 | +4.9MB   |
| trimmed orderings (<= 2 doubles high arity) | ~1270 | +1.66MB  |
| count shapes (sorting trick)             | ~700  | +0.76MB  |
| final (arity <= 7, varargs <= 5, upcall trim) | ~530  | +0.55MB (+0.79%) |
| + 299 compiled trampolines, minus dead descriptors | ~220 + 299 | +1.77MB (+2.5%) |

Call cost, native image: FFM interpreted 4777ns -> trampoline 63ns. JVM
(FFM, JIT-compiled): 123ns.

Call overhead: ~4.8us per call in the native image (`invokeWithArguments` +
MethodHandle interpretation). Fine for per-row and per-frame calls (tetris
draws ~200 rects/frame), wrong for per-element loops - pass buffers instead.

## Current limits (the user contract)

Up to 7 args, of which at most 6 pointer/integer, at most 6 doubles, at most
4 floating args when any is `:float`. Float return needs <= 4 args. Variadic:
<= 5 args total, <= 3 fixed, <= 2 doubles, no floats. Callbacks: <= 4 args,
<= 2 doubles, void or integer return. Struct-by-value unsupported.

## Known gaps

- Struct-by-value. Arguments are solvable inside the scalar family by ABI
  classification (AArch64 HFAs decompose into float args, small composites
  into 1-2 longs, large ones pass indirectly). Struct RETURNS cannot be faked
  with scalars (two return registers), and need a small registered family of
  real struct layouts.
- Windows, untested. The metadata uses JNI type names (`"jlong"`), fixed-size
  on every platform (C `"long"` would be 32-bit on Windows). Sorting is
  unsound there (positional registers), so `sort-permutation` returns nil on
  Windows and `bb script/gen_ffi_metadata.clj windows` emits ordered shapes -
  the Windows build must run that mode before script/uberjar, which is not
  wired into CI yet.
- The static musl build cannot dlopen at all.
- errno: bindable today via `__errno_location`/`__error`, or properly via
  `captureCallState` descriptor variants (not registered).
- Callback lifetime: solved - each callback gets its own shared Arena and
  `free-callback` closes it (stub freed, wrapped fn GC-able). Freeing a
  callback C still holds is undefined behavior, as in jolt.
- Test coverage: `test-resources/ffi_test_lib.c`, compiled on demand by the
  test suite, proves argument order for mixed shapes, narrowing edges,
  va_list contents, typed callbacks, arity 7/10, and callbacks invoked from
  a C-created thread (works in the native image: the upcall stub attaches
  the unknown thread to the isolate). This is what caught the FP-ordering
  bug.
- Unregistered signatures fail with a raw `MissingForeignRegistrationError`.
  Should be caught and rephrased with the family limits.

## Files

- `src/babashka/ffi.clj` - the layer (widening, sorting, marshaling, varargs,
  callbacks)
- `script/gen_ffi_metadata.clj` - generates
  `resources/META-INF/native-image/babashka/ffi/reachability-metadata.json`
  (never edit the JSON by hand)
- `test/babashka/ffi_test.clj` - regression tests (JVM and native via
  `BABASHKA_TEST_ENV`), including a generator-freshness check
- `ffi-smoke.clj`, `ffi-sqlite.clj`, `ffi-raylib.clj`, `ffi-tetris.clj` -
  runnable demos in the repo root
- jolt reference: `~/dev/jolt/stdlib/jolt/ffi.clj`, examples in
  github.com/burinc/b12n-raylib-jlt
