# ADR 0003: the nREPL server runs from bundled source

## Status

Recorded 2026-09-11 on branch `nrepl-from-source`. Decisions were taken in
chat on 2026-09-11, the notes are in the maintainer's dev-todo.

## Context

babashka's nREPL server was babashka.nrepl, a compiled library with its own
protocol implementation. It kept all REPL state (`*ns*`, `*1`, `*e`, the
`:thread-bind` vars) on the connection thread, and a session was only an id
in a set. Two sessions on one connection shared that state
(babashka/babashka.nrepl#72), and `interrupt` could not exist because the
thread that reads messages was the thread that evaluated them.

The library also lived in two worlds: it bound Clojure's `*1` and sci's
`*1` side by side, and every change meant a native image rebuild to try.
Nothing of it was exposed to scripts beyond `start-server!`.

## Decisions

### 1. Run nREPL itself, from bundled source

bb ships nREPL 1.7.0 the way it ships tools.deps and tools.build:
`script/vendor_bundled_sources.clj` copies the files into
`resources/src/babashka/nrepl/`, the image serves them through the bundled
source loader, and a jar on the classpath does not override them
(`bundled-first`). nREPL's seven Java classes go to `src-java/nrepl/`.

Rejected: a second implementation of nREPL's middleware protocol. It would
have had to reimplement sessions, the print and caught middleware, and
`describe`, and would drift from nREPL. Running nREPL gives all of it,
including `interrupt`, and an upgrade is a version bump in the script.

Rejected: keeping the compiled babashka.nrepl and fixing #72 inside it. The
fix rewrote its core anyway, and would have kept the two binding worlds.

### 2. Patches are minimal and marked

The script writes every patch, so an upgrade re-applies them. A `patch`
keeps the upstream form under `#_` with a `BB-PATCH` note; a `subst` is a
rename inside a form where a discarded copy has no place. Eight files are
patched, for what sci cannot run as it is:

- Compiler internals: `Compiler/eval` (called as `clojure.core/eval`, since
  the evaluator destructures `eval` from the message), `Compiler/SOURCE`
  (`*file*`), `Compiler/LOADER`, the per-file compiler bindings, the
  reflective column setter, `CompilerException` and `ReaderException`
  (sci reader errors carry `:sci.error/parse`).
- `deftype` and `defrecord` implementing a Java interface, which sci does
  not support: `FnTransport`, `BufferedOutputChannel` and `Server` become
  `reify` or lose the interface.
- `nrepl.transport.Transport` imported as a class: it is a sci protocol.
- `clojure.core/pr-on`, private and absent in sci.
- The version string, a jar resource the image cannot read.

Two of nREPL's Java classes are patched as well: `SessionThread` and
`DaemonThreadFactory` pass an 8 MB stack size, the main thread's. A thread
in the image otherwise gets a sixteenth of that, and an eval over nREPL
overflowed at a sixteenth of the depth the same code reaches as a script
(malli validation of recursive data: 200 levels against 3,200).

Everything else that nREPL needed was interop bb did not expose yet, and
was registered rather than patched: `RT/classForName`, `Var.bindRoot`,
`Thread$State`, `NoSuchMethodException`, `SSLServerSocket`, four
`java.net` and `java.nio.channels` classes, and the nREPL Java classes.
`clojure.main/root-cause` and `skip-if-eol` were added to bb's
`clojure.main`.

### 3. Stand-ins for what the image cannot do

Four namespaces are bb's own files at nREPL's paths: `nrepl.bencode`
delegates to the compiled `bencode.core`, since byte-level parsing is too
slow interpreted; `nrepl.util.completion` completes against sci's
namespaces through bb's completion code; `nrepl.util.classloader` returns
nil, an image has no `DynamicClassLoader`; `nrepl.tls` throws, TLS is not
supported. Dropped: `cmdline`, `spec`, `tls_client_proxy`, the JVMTI agent.

### 4. babashka's ops are nREPL middleware

`babashka.nrepl.impl.server/wrap-babashka`, interpreted, carries a
descriptor and handles `complete` (the old name of `completions`),
`eldoc`, `info`, `ns-list` and `classpath`, and adds babashka's versions
to `describe` through a wrapping transport. The completion and lookup
code stays compiled in `babashka.impl.nrepl.sci`, since it reads sci's
context internals, and is exposed to the interpreted side as
`babashka.nrepl.impl.sci`. User middleware is a var with an nREPL
descriptor, given as `:middleware` to `start-server!`; the old `:xform`
option is gone.

### 5. The shim

`babashka.nrepl.server/start-server!` (compiled) evaluates the interpreted
`start-server!` in the sci context and wraps the handler so that
`sci.ctx-store` is bound on nREPL's handler threads, which convey no
bindings. nREPL runs on daemon threads, so the interpreted side starts one
non-daemon thread that lives while the server socket is open, which keeps
`bb -e "(start-server! ...)"` alive as before. `bb nrepl-server` and
`--nrepl-server` are unchanged.

### 6. The behavioral tests are the contract

`test/babashka/impl/nrepl_server_test.clj` passes with its `nrepl-test`
body unchanged, on the JVM and natively. Only its JVM start branch changed:
it starts the server through bb's own entry point, because a bare
`sci/init` context has no source loader.

## Measurements

Local macOS aarch64, against master `db8e2881`: image 74,357,616 bytes
against 74,407,216, code area 32.11 MB against 32.16 MB, image heap equal.
Loading the server from source costs about 20 ms; the port opens about
107 ms after launch, as before.

## Consequences

- #72 is fixed by nREPL's session middleware: `*1`, `*e`, `*ns*` and the
  other bindings live per session, a reconnect can reuse a session id.
- `interrupt` exists, cooperative: it interrupts the session thread; a tight
  loop keeps running until sci's interrupt hook is wired into bb.
- A newer nREPL is a bump of `nrepl-version` in the vendoring script; the
  script reports every upstream file it does not know.
- TLS and `nrepl.cmdline` are not available.
- The babashka.nrepl submodule is no longer on the source paths and can be
  removed.
