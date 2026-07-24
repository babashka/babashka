# ADR 0001: CLI support in bb tasks

## Status

Accepted on feature branch `cli-tree-tasks`, pending merge. Library support
released in babashka.cli through 0.12.82, pinned in bb.

## Context

A bb task could already parse args by calling `(exec 'my/fn)` or babashka.cli
directly from the task body. That gives no `--help`, no shell completion, and no
subcommands without hand-written code in every task.

The goal is declarative CLI tasks that route through `babashka.cli/dispatch`, so
a task gets help, completion and a command tree from spec data. bb.edn is read as
data and never evaluated. Function metadata (`:org.babashka/cli`) is evaluated and
can hold function objects. That split decides where each piece of config lives.

## Decisions

### 1. Split config by whether it holds a function

bb.edn holds routing and pointers: which fn handles the task, the command tree,
and runner-level defaults. Everything that contains a function lives in the
function's `:org.babashka/cli` metadata: `:spec`, `:args->opts`, `:error-fn`,
`:coerce`, `:restrict`, `:epilog`. The spec and help live next to the fn, and
`bb -x` reuses the same metadata.

### 2. Flat task keys `:exec-fn` and `:cmd`

Write a CLI task flat with `:exec-fn` or `:cmd` at the task level.
`hoist-cli-keys` folds them into `:cli` at bb.edn load, so every consumer
(assembly, help, completion, task listing) sees one `:cli` shape. The nested
`:cli {...}` form still works. Giving a handler both places, or `:cmd` in both
places, is a config error.

```clojure
;; bb.edn
{:tasks
 {:cli   deploy/base-opts
  dev    {:exec-fn dev/run}
  deploy {:cmd {"lock"   {:fn deploy/lock}
                "unlock" {:fn deploy/unlock}}}}}
```

### 3. Ordered commands are written as a vector, not recovered

An edn map keeps insertion order up to 8 keys, so a bigger `:cmd` map reaches
bb already unordered and help would list its commands in hash order. Write it
as a vector of `[name command]` pairs instead, which babashka.cli takes as an
ordered command list. `:cmd-order` remains for setting an order explicitly.

```clojure
deploy {:cmd [["lock" {:exec-fn deploy/lock}]
              ["unlock" {:exec-fn deploy/unlock}]]}
```

bb used to recover the order instead, reading the raw bb.edn text with
rewrite-clj to see how the map was written. That was about 100 lines, it parsed
bb.edn on every startup in a directory that has one, and it guessed at intent:
it had already shipped one bug where the flat `:cmd` form was not recovered
because hoisting had moved the key before the text was read. A vector says what
it means, so the recovery is gone.

Everything that walks a `:cmd` therefore keeps its shape, rather than rebuilding
it as a map and throwing the order away.

### 4. `:exec-fn` versus `:cmd`

`:exec-fn` is an opts-only handler that receives the parsed opts map, like
`bb -x`. `:cmd` is a command tree for dispatch. A task may carry both: the
`:exec-fn` is the default handler and `:cmd` adds subcommands.

### 5. No `:cmd` on function metadata

Command trees live only in bb.edn. `bb -x` never read `:cmd` from function
metadata, so a `:cmd` there is ignored. `-cli-dispatch` and `-resolve-cli-specs`
drop it. This keeps routing in the data file and specs on the fn.

### 6. Handler-less groups report "No command given."

A `:cmd` group with no handler dispatches to no command when called bare, and
babashka.cli reports "No command given." A group with a default handler
(`:exec-fn` or a task body plus `:cmd`) falls through to that handler instead.
A `:task` that is a qualified symbol is a body like any other, so it is the
default action for its group and does not turn off dispatch.

### 7. Runner-level defaults: map or symbol

`:tasks {:cli ...}` sets defaults for every CLI task. It accepts a map (data
only) or a symbol naming a def of a map. `-resolve-cli-tasks-defaults` resolves
the symbol via the script's `requiring-resolve`. The symbol form carries
defaults that include functions, such as a shared `:error-fn` or `:restrict`, so
a command group with no handler fn still gets a custom error handler. One entry
replaces the same `base-opts` repeated across many command namespaces.

The defaults sit between bb's own dispatch opts: they override `:help`, so a
runner can turn auto-help off, and they do not override `:prog`, so help always
names the task it belongs to.

### 8. Config errors name the task and the key

A bb.edn that points at something absent is reported, not left to fail later:
an `:exec-fn` or `:fn` whose var does not resolve, and a runner-level `:cli`
symbol that does not resolve or does not name a map. Without that the generated
code calls nil and the user gets a bare NullPointerException from a file they
did not write.

The error names the task and the key for both ways a symbol fails, since they
take different routes: a missing var resolves to nil, a missing namespace
throws out of `requiring-resolve`. The underlying message is appended and the
original exception kept as the cause, because a typo in bb.edn usually reaches
the user as the namespace not being on the classpath.

This is invocation-time only, so a stale name never breaks completion or
`bb tasks`, which stay best-effort.

Shape errors are caught earlier, when bb.edn is read, and name the task: a
`:cli` that is not a map, a `:cmd` command pointing straight at a function
rather than at a map, and a `:task` body next to a `:cli` `:fn`, which would
never be called. A bare symbol as a command is rejected rather than read as
sugar for `{:exec-fn f}`, because it could as well mean `:fn` or bb's own
"task is a qualified symbol" form, and the error says which to write.

`:exec-fn` is deliberately not part of that last check: it takes priority over
a body, which is how a command group gets a default action.

A config error raised while reading bb.edn is printed as a message with its
exit code, not a stack trace. During a completion callback it also emits the
file-completion marker, so a broken bb.edn leaves the shell its own completion
instead of nothing.

### 9. `:error-fn` is never bb.edn data

dispatch would treat a symbol `:error-fn` as a map lookup and swallow every
error. `assert-no-edn-error-fn` rejects `:error-fn` in a bb.edn task node. Put it
in the function's `:org.babashka/cli` metadata, or in the defaults var referenced
from `:tasks {:cli my.ns/defaults}`.

### 10. Dispatch and completion resolve the same `:cli` shape

`-cli-dispatch` (invocation) and `completion-program` (completion) both read
`(:cli (:tasks bb-edn))`. Both resolve a symbol runner-level `:cli` the same way.
`completion-program` emits `-resolve-cli-tasks-defaults` into the completion code
so symbol defaults reach completion, not just invocation.

### 11. Deps run as a parser-selected pre-step

For a `:cli` task, `:depends` runs right before the command fn the parser
selects, root body or subcommand, and only on a successful parse. dispatch never
calls a command fn for `--help`, `-h` or a parse error, so `bb task --help` shows
help without running deps. The parser decides when deps run, like cobra's PreRun,
not a scan of raw args.

Only the dependency bodies move into the thunk. Their `:requires` and
`:extra-paths` / `:extra-deps` stay in the program preamble, so `--help` still
processes them: it can load a dependency's namespaces and resolve its deps, and
it fails if one of them is missing.

Moving them into the thunk was tried and reverted. sci analyzes the whole thunk
as a single form, so a `require` inside it has not run when a body that uses its
alias is analyzed: a dependency declaring `:requires ([helper :as h])` and
calling `(h/hi)` failed with "Unable to resolve symbol: h/hi" in the analysis
phase, on `--help` and on a real run alike. For the same reason a handler
supplied by a dependency's `:extra-paths` could not resolve, because
`-cli-dispatch` resolves handler symbols while building the tree, before the
thunk runs. Deferring these would need the emitter to decide statically, from
the raw args, whether this is a help invocation, which is the arg scanning this
design rejects.

This applies to a non-parallel task. A parallel task keeps its dependencies as
forms ahead of the target, because parallel deps rely on launching their
channels before the target waits on them, so `--help` starts them too.

### 12. `:enter` and `:leave` apply to handlers, not just bodies

A task with a `:task` body has its `:enter` / `:leave` wrapped around the body.
A task whose handler is an `:exec-fn` or `:fn`, including a command group leaf,
has them applied to the handler that dispatch selects. They run on the same
terms as `:depends`: after a successful parse, never for `--help` or a parse
error.

### 13. Completion offers task names, files and non-deprecated global options

Completing the first word offers task names plus a file-completion marker, so
`bb file.clj` stays as first-class as `bb task`. A dash-prefixed first word
offers bb's global options.

Those come from `option-table` in `babashka.main`, which is also what renders
the Global opts and Evaluation sections of `bb --help`. One definition, two
readers: a curated second copy for completion had already drifted from the help
text by five options. `main` passes the pairs to `completion-program`, which
keeps the task namespace free of bb's own option list.

The deprecated stream options `-i`, `-I`, `-o`, `-O` and `--stream` are not in
the table's completion output. Completion is a recommendation, so it must not
advertise options that new scripts should not use. They keep working when
typed, and the help text still documents them.

Completing a task's arguments delegates to `babashka.cli/dispatch` over the
task's `:cli` tree. A task without `:cli` emits the file-completion marker
rather than nothing, so claiming the `bb` compdef does not take away the file
completion the shell offered before.

A completion callback never runs what is on the line being completed. The
completed words go through bb's normal option parsing, so that `--config` and
friends point completion at the right bb.edn, and the resulting opts are then
cut down to what completion needs. Everything that would evaluate, start or
write something is dropped: an `-e` expression, `--init`, subcommands like
`clojure` and `nrepl-server`, uberjar post-processing, `--help` and `--version`
output. Without that, `bb -e '(...)'` followed by TAB evaluates the expression
before the user has pressed enter. The list is a whitelist, since a mode left
in would run on a keystroke.

Completion runs the task's `:extra-paths` / `:extra-deps` and its `:requires`
first, because the handler may live on that classpath or be named through a
require alias. This is the one thing completion does execute, and it is why an
`:extra-deps` that is not in the local cache can make the first TAB pause on a
download. A task with an explicit `:doc` avoids the same cost during task-name
completion, which otherwise loads a namespace to read a docstring. It applies the runner-level defaults in the same precedence
dispatch uses, so a runner that turns `:help` off does not get `--help` offered
as a candidate.

That setup runs inside the same `try` as the dispatch it prepares, and any
failure falls back to the file-completion marker. The shell discards stderr, so
an uncaught error would read as "no candidates" and take file completion down
with it. A bb.edn naming a namespace that is not there is a normal state to
complete in, not an exceptional one.

For the same reason a task doc is best-effort. Deriving one loads the fn's
namespace, and one task with a missing namespace must not stop `bb tasks` from
listing the others or completion from offering them.

### 14. `:doc` as a vector of lines

A task `:doc` may be a vector of strings. `join-docs` joins it with newlines into
the string every consumer expects.

## Library support in babashka.cli

This feature drove these additions, used by the task layer above.

- 0.12.80: ordered `:enum` values for validation, help and completion, with
  `:validate` derived from `:enum` and `enum` taking precedence when both are
  given. `:doc` and `:epilog` as a vector of lines. Per-element validation for
  repeatable options. `:args->opts` entries shown under `Arguments:` in help.
  Sorted set-valued `:validate` in help.
- 0.12.81: a required `:inherit` option is no longer reported missing when
  supplied after its command.
- 0.12.82: `:msg` is populated in the `:error-fn` data for dispatch command
  errors (`:no-match`, `:input-exhausted`), like option errors already carry it,
  so an error handler can read `:msg` uniformly.

## Rejected and deferred

- Overlay of function metadata onto a bb.edn task node. Deferred. Most config
  folds into `:exec-fn` or fn metadata, so the overlay had no remaining use.
- Inline `:cli {:spec ...}` in bb.edn as the taught form. Dropped from the docs.
  Specs live on the fn, where `bb -x` reads them too.
