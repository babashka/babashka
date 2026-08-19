# 2. Task imports, per task

A task can be a reference to one a library ships as data:

```clojure
{:deps {org.acme/ci {:mvn/version "1.0"}}
 :tasks {test   {:import ci.tasks/test}
         verify {:import ci.tasks/deploy :doc "our wording"}}}
```

The lib part of the symbol names `ci/tasks/tasks.edn` on the classpath, a map
of task definitions and nothing else. The name part picks the task.

A competing shape, `:imports` with `:refer` lists, lives on the
`tasks-imports` branch. This one is the primitive: that one can become sugar
over it for adopting a whole suite with a `From <lib>:` listing.

## Principle

Every addressable task name is statically readable from bb.edn; everything
else may be lazy. Parsing, collision freedom, listing position and
per-closure lib reading all follow from it. The eager variant lost them not
through its `:refer` syntax, whose names are static too, but by making hidden
transitive members addressable, which only the lib file could name. Any sugar
built over this keeps the rule: referred names join the static set, hidden
members stay sealed.

## Decisions

- The bb.edn key is the local name. Rename is writing a different key,
  collisions cannot happen, `bb tasks` lists the task at its own position:
  the entry says "my task, implemented elsewhere", like `:exec-fn` does.
- The task name is a literal key, so the parser needs nothing from the lib.
  Running a task reads only the libs its `:depends` closure reaches, to
  fixpoint: an imported task may depend on a local key that is itself an
  import. `bb tasks`, `bb doc` and completion read every lib, since they
  describe everything. `bb --version` and `bb -e` touch no lib file at all,
  and `-cp` works as a source of imports.
- Local keys override imported ones: `:doc`, `:private`, hooks.
- Docs resolve in three steps, each lazier: the local `:doc`, the lib's
  `:doc` (a resource read, data), the `:exec-fn` docstring (loads code, like
  a local task without `:doc` already does).
- Transitive `:depends` come along under hidden local names,
  `-<lib>_<name>`, dash-prefixed: unlisted, not addressable, and free of the
  lib's naming. Within one lib, a dependency an entry also imports resolves
  to that entry's local name, so the graph stays deduplicated.
- A lib task may use the shorthand form, a bare body: it imports as
  `{:task body}`, so local overrides apply to it like to any map.
- The file may hold task definitions only. File-level keys are an error. The
  consumer's `:enter`/`:leave` wrap imported tasks.
- The library's code dependencies live in its own deps.edn and arrive
  transitively. Per-task `:extra-deps` stays what it is.
- A lib's tasks.edn may itself import: a materialized definition that still
  holds an `:import` is just another pointer, resolved on the next round.
  Transitivity stays sealed, hidden members all the way down. A pointer that
  survives resolution of its own lib is a cycle and errors as one.
- Import errors are loud for whatever reaches the import, like an
  unresolvable `:deps` entry: a task whose closure needs the lib, `bb tasks`,
  completion. A task that never reaches it runs, and `bb -e` and `--version`
  are untouched, which the eager variant could not offer. An invalid
  `:import` value is an error for every consumer.
- Several entries may import the same task: each materializes, and a hidden
  dependency on it rewrites to the first alias in sorted order, so it runs
  once. Resolution holds a lock: concurrent `run` calls from parallel bodies
  must not discard each other's materialization.
- A `:depends` name the lib does not define errors lazily at assembly,
  `No such task`, exactly like a local dangling `:depends`.

## Security notes

- Resolution executes nothing: an EDN resource read, no reader eval, no
  file-level `:init` possible. Code runs only when an imported task is
  deliberately invoked. Pinned by the load-noise tests.
- No ambient authority: a dependency cannot add or shadow a task. Every
  addressable name is an `:import` the consumer wrote.
- Inherited, not new: any jar earlier on the classpath can supply another
  lib's `tasks.edn` path, like namespace squatting generally.

## Decision matrix

| # | context | status |
|---|---------|--------|
| 1 | direct invocation | tested |
| 2 | `(run 'imported)` in a body | same merged map, inherited |
| 3-6 | `:depends`, `--parallel`, plain and CLI targets | tested: chains, shared hidden deps run once, hidden CLI dep handler, each also under `run --parallel` |
| 7 | `--help` | tested |
| 8 | completion | tested |
| 9 | spec merge | tested, inherited by the data merge |
| 10 | shared dependencies | one graph, hidden members deduplicated per lib |
| 11 | graph or body | data merge, nothing bypassed |
| 12 | hooks | consumer's wrap imported tasks, as A probed |
| 13 | failure | config errors exit 1, loud |
| 14 | native image | not probed, compile once before merge |
| 15 | version | consumer needs the bb release carrying this |

## Open

- The `:imports`/`:refer` sugar for whole-suite adoption, with `From <lib>:`
  provenance in `bb tasks`.
- `:min-bb-version` in the imported file. Uberjars carrying imports.
