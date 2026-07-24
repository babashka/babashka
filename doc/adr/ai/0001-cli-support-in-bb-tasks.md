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

### 3. `:exec-fn` versus `:cmd`

`:exec-fn` is an opts-only handler that receives the parsed opts map, like
`bb -x`. `:cmd` is a command tree for dispatch. A task may carry both: the
`:exec-fn` is the default handler and `:cmd` adds subcommands.

### 4. No `:cmd` on function metadata

Command trees live only in bb.edn. `bb -x` never read `:cmd` from function
metadata, so a `:cmd` there is ignored. `-cli-dispatch` and `-resolve-cli-specs`
drop it. This keeps routing in the data file and specs on the fn.

### 5. Handler-less groups report "No command given."

A `:cmd` group with no handler dispatches to no command when called bare, and
babashka.cli reports "No command given." A group with a default handler
(`:exec-fn` or a task body plus `:cmd`) falls through to that handler instead.
A `:task` that is a qualified symbol is a body like any other, so it is the
default action for its group and does not turn off dispatch.

### 6. Runner-level defaults: map or symbol

`:tasks {:cli ...}` sets defaults for every CLI task. It accepts a map (data
only) or a symbol naming a def of a map. `-resolve-cli-tasks-defaults` resolves
the symbol via the script's `requiring-resolve`. The symbol form carries
defaults that include functions, such as a shared `:error-fn` or `:restrict`, so
a command group with no handler fn still gets a custom error handler. One entry
replaces the same `base-opts` repeated across many command namespaces.

The defaults sit between bb's own dispatch opts: they override `:help`, so a
runner can turn auto-help off, and they do not override `:prog`, so help always
names the task it belongs to.

### 7. `:error-fn` is never bb.edn data

dispatch would treat a symbol `:error-fn` as a map lookup and swallow every
error. `assert-no-edn-error-fn` rejects `:error-fn` in a bb.edn task node. Put it
in the function's `:org.babashka/cli` metadata, or in the defaults var referenced
from `:tasks {:cli my.ns/defaults}`.

### 8. Dispatch and completion resolve the same `:cli` shape

`-cli-dispatch` (invocation) and `completion-program` (completion) both read
`(:cli (:tasks bb-edn))`. Both resolve a symbol runner-level `:cli` the same way.
`completion-program` emits `-resolve-cli-tasks-defaults` into the completion code
so symbol defaults reach completion, not just invocation.

### 9. Deps run as a parser-selected pre-step

For a `:cli` task, `:depends` runs right before the command fn the parser
selects, root body or subcommand, and only on a successful parse. dispatch never
calls a command fn for `--help`, `-h` or a parse error, so `bb task --help` shows
help without running deps. The parser decides when deps run, like cobra's PreRun,
not a scan of raw args.

The dependencies' own `:requires` and `:extra-paths` / `:extra-deps` go into the
same thunk. Emitting them in the program preamble, where they used to be, meant
`--help` loaded dependency namespaces and resolved their deps: help could
download a dependency, run a namespace's load side effects, or fail outright on
a namespace that is not there. The target task's own `:requires` and extras stay
eager, since help needs its handler's metadata.

This applies to a non-parallel task. A parallel task keeps its dependencies as
forms ahead of the target, because parallel deps rely on launching their
channels before the target waits on them, so `--help` still starts them.

### 10. `:enter` and `:leave` apply to handlers, not just bodies

A task with a `:task` body has its `:enter` / `:leave` wrapped around the body.
A task whose handler is an `:exec-fn` or `:fn`, including a command group leaf,
has them applied to the handler that dispatch selects. They run on the same
terms as `:depends`: after a successful parse, never for `--help` or a parse
error.

### 11. Completion offers task names, files and non-deprecated global options

Completing the first word offers task names plus a file-completion marker, so
`bb file.clj` stays as first-class as `bb task`. A dash-prefixed first word
offers bb's global options from a curated list in `global-opt-completions`.

The deprecated stream options `-i`, `-I`, `-o`, `-O` and `--stream` are left out
of that list. Completion is a recommendation, so it must not advertise options
that new scripts should not use. They keep working when typed.

Completing a task's arguments delegates to `babashka.cli/dispatch` over the
task's `:cli` tree. A task without `:cli` emits the file-completion marker
rather than nothing, so claiming the `bb` compdef does not take away the file
completion the shell offered before.

### 12. `:doc` as a vector of lines

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
