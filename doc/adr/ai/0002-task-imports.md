# 2. Task imports

A library ships tasks as data: `<lib-path>/tasks.edn` on the classpath, a map
of task definitions and nothing else. The consumer imports them in bb.edn:

```clojure
{:deps {some-other/lib {:mvn/version "1.0.0"}}
 :tasks {:imports ([some-other.lib :refer [some-task]])}}
```

## Decisions

- An import is data merged into data. The imported tasks behave as if they
  were written in bb.edn, so every task feature applies to them by
  construction rather than by re-implementation.
- Explicit `:refer` only. A referred task brings its transitive `:depends`
  along, unlisted. A name that is already a task is an error.
- The file may hold task definitions only. File-level keys such as `:init`
  are an error: whose `:init` runs first has no good answer, so the question
  is not asked. The consumer's `:enter`/`:leave` wrap imported tasks.
- The library's code dependencies live in its own deps.edn and arrive
  transitively. Per-task `:extra-deps` stays what it already is, laziness for
  a heavy dependency of one task. `tasks.edn` has no dependency mechanism.
- Imports resolve at bb.edn read time against the classpath bb.edn itself
  declares, because the arg parser needs the imported names to tell a task
  from a file. An import belongs to project config, not to a `-cp` flag.
- Import errors are loud and fail every invocation, like an unresolvable
  `:deps` entry already does. Considered and rejected: warn-and-skip.
- A `:depends` name the lib does not define errors lazily at assembly,
  `No such task`, exactly like a local dangling `:depends`. Considered and
  rejected: an import-time check naming the lib.
- `bb tasks` lists referred tasks under `From <lib>:`, after the local ones,
  the same convention as `Inherited options:` in help: what arrives from
  elsewhere shows as arriving from elsewhere.

## Decision matrix

| # | context | status |
|---|---------|--------|
| 1 | direct invocation | tested |
| 2 | `(run 'imported)` in a body | probed, runs with transitive deps |
| 3 | `(run 'x {:parallel true})` | probed via `bb run --parallel` |
| 4 | in `:depends` of a plain local task | probed, one merged graph |
| 5 | imported CLI task as dep of a local CLI target | probed, handler gets its declared opts, spec under `Inherited options:` |
| 6 | `bb run --parallel` | probed |
| 7 | `--help` | tested; a broken import fails loudly, by decision |
| 8 | completion | tested; same loud failure applies |
| 9 | spec merge, `:restrict` | tested, inherited by the data merge |
| 10 | shared dependencies | one graph, dedup holds; hidden-member naming open |
| 11 | graph or body | data merge, nothing bypassed |
| 12 | hooks | probed, consumer's `:enter`/`:leave` wrap imported tasks |
| 13 | failure | config errors exit 1; scope-of-failure decided loud |
| 14 | native image | not probed, compile once before merge |
| 15 | version | consumer needs the bb release carrying this |

## Known costs

- With `:imports` present, deps resolve before arg parsing, so `bb --version`
  and `bb -e` pay for it, and `-cp` no longer suppresses bb.edn deps.
- `-Sforce` may not refresh the `tasks.edn` read, since the pre-parse
  resolution wins within that invocation.

## Open

- `:rename`.
- Hidden transitive members keep their names: two imports can collide on a
  `-helper`, and a local task can silently depend on one.
- `:min-bb-version` in the imported file.
- Uberjars carrying `:imports`.
