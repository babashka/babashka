# babashka.tasks decision matrix

Fill this in before adding or changing anything in `babashka.tasks`. State the
behavior of the change in each context below, with a probe or a test. An
unstated row is where the next silent no-op lives.

## Contexts

| # | Context | State for the change |
|---|---------|----------------------|
| 1 | `bb <task>` | direct invocation |
| 2 | `(run 'x)` in a body | nested, serial |
| 3 | `(run 'x {:parallel true})` | |
| 4 | in `:depends` of a plain target | runs? contributes what? |
| 5 | in `:depends` of a CLI target | who calls the handler, with which opts |
| 6 | `bb run --parallel <task>` | siblings concurrent, first failure aborts |
| 7 | `bb <task> --help` | must describe, must not execute anything |
| 8 | shell completion | dep classpath present, nothing printed into candidates |
| 9 | CLI tasks (`:exec-fn` / `:cmd`) | one parse, spec merge order, `:restrict` |
| 10 | shared dependencies | dedup and ordering hold (the diamond) |
| 11 | graph or body | does the change bypass the task graph? |
| 12 | hooks | `:enter` / `:leave` / `*task*` binding around it |
| 13 | failure | exit code, `Error in task: X` framing, fail-fast or join |
| 14 | native image | threads and dynamic vars in the compiled bb |
| 15 | version | works on the release consumers pin with `:min-bb-version` |

## The incidents behind the rows

- A CLI task named in `:depends` silently did nothing (rows 4, 5). #2011.
- A `parallel` macro over thunks bypassed the graph: a task reached from two
  branches ran twice, concurrently. ductile's `npm-install` diamond made that
  concrete (rows 10, 11). Parked on the `tasks-parallel` branch, unmerged.
- `--help` ran dependency bodies, later a dependency handler under
  `--parallel` (row 7).
- Completion fell back to file completion when a dependency's handler lived on
  the dependency's own `:extra-paths`, and loading a namespace printed into
  the candidate list (row 8). #2017, #2018.
- A task's own `:cli` spec replaced the runner-level one, and `merge-opts`
  clobbered a spec with nil (row 9). #2009, #2010.
- `exec` re-parses argv, so `:restrict` bites whichever side does not own an
  option (row 9).
- An alias `:deps` replaced the project deps. Only CircleCI broke, because
  GitHub Actions runs the lein path (rows 14, 15): green CI on one system
  proves nothing about the other.

## Process

- Probe the gap on master first. Paste real output, not the expected output.
- Every new test must fail without its fix. Mutate the source to check.
- A CHANGELOG line describes the change against the last release tag, not
  against unreleased master.
