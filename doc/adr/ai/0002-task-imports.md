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

## Decisions

- The bb.edn key is the local name. Rename is writing a different key,
  collisions cannot happen, `bb tasks` lists the task at its own position:
  the entry says "my task, implemented elsewhere", like `:exec-fn` does.
- The task name is a literal key, so the parser needs nothing from the lib.
  The file is read by the first consumer of the task map: running a task,
  `bb tasks`, `bb doc`, completion, or `babashka.tasks/run` in a script.
  `bb --version` and `bb -e` touch no lib file at all, and `-cp` works as a
  source of imports.
- Local keys override imported ones: `:doc`, `:private`, hooks.
- Docs resolve in three steps, each lazier: the local `:doc`, the lib's
  `:doc` (a resource read, data), the `:exec-fn` docstring (loads code, like
  a local task without `:doc` already does).
- Transitive `:depends` come along under hidden local names,
  `-<lib>_<name>`, dash-prefixed: unlisted, not addressable, and free of the
  lib's naming. Within one lib, a dependency an entry also imports resolves
  to that entry's local name, so the graph stays deduplicated.
- The file may hold task definitions only. File-level keys are an error. The
  consumer's `:enter`/`:leave` wrap imported tasks.
- The library's code dependencies live in its own deps.edn and arrive
  transitively. Per-task `:extra-deps` stays what it is.
- Import errors are loud for whatever consumes the task map, like an
  unresolvable `:deps` entry. An invocation that consumes none, `bb -e` or
  `--version`, is untouched, which the eager variant could not offer.
- A `:depends` name the lib does not define errors lazily at assembly,
  `No such task`, exactly like a local dangling `:depends`.

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
- Two entries importing the same lib task: allowed, the dependency rewrite
  picks one of them.
- `:min-bb-version` in the imported file. Uberjars carrying imports.
