# bb tasks CLI: design decisions (brainstorm output, 2026-06-12)

All decided unless marked OPEN.

## D1. Subcommands are nested TASKS

Children are ordinary task maps under `:tasks`; leaves are plain expressions
(tasks are expressions, not functions). `:cli {:cmd ...}` with `:fn` leaves
(the first branch implementation) dies. Structure = `:tasks` nesting;
options = `:cli {:spec ...}` per task. Nesting flattens internally so the
existing machinery (depends, parallel, assembly, key-order) keeps working.

## D2. :opts is THE task invocation parameter; parsing is one producer

```
producers                                  consumer
---------                                  --------
CLI:      bb maintenance enable --env x     \
run:      (run 'enable {:opts {:env "x"}})   >   (:opts (current-task))
depends:  :depends [{:task enable            /    + let-bound option names
                     :opts {:env "x"}}]
```

- (run 'task {:opts ...}): run's existing options map (today only
  :parallel) gains :opts.
- :depends map entries = same channel (the reserved edge-opts form).
- Defaults from the spec are ALWAYS applied, whatever the producer - body
  can rely on {:env "staging"} existing.
- Only CLI input is coerced/validated; directly-passed opts are trusted
  Clojure data (precedent: :exec-args).
- NO merging across producers; innermost producer wins, people can merge
  themselves.

## D3. Body ergonomics: option names are let-bound around the body

```clojure
maintenance
{:doc "Maintenance mode"
 :tasks {enable {:doc "Enable maintenance"
                 :cli {:spec {:env {:default "staging"}
                              :verbose {:coerce :boolean}}}
                 :task (ops/enable {:env env :verbose verbose})}}}
```

bb generates `(let [{:keys [env verbose]} (:opts (current-task))] <body>)`.
LET, not intern: interning would leak across tasks sharing the run
namespace (a dependency's `port` visible in the target's body). Lexical
shadowing edge: an option named `run`/`shell` shadows the helper inside
that body - documented, pick another name.
`(:opts (current-task))` remains available (e.g. to forward all opts).

## D4. exec: survives unchanged as an idiom, demoted from "the way"

Fn-metadata-driven tasks keep using `(exec 'foo/bar)` exactly as today.
OPEN (small): when a task already has :opts set by a producer, should exec
pass them through instead of re-parsing argv? Lean yes, decide during
implementation.

## Carried decisions (from earlier rounds, still standing)

- cli 0.11.72 bundled; *exit-fn* sci bridge.
- --help intercepted only for tasks with :cli; help must be pure (no
  :depends, no :requires, no :init) - rendered from bb.edn data.
  Consequence of D1+help purity: specs/docs shown in help come from bb.edn
  (fn metadata is runtime-only, exec's concern).
- Colon names stay meaningless convention; no structure derived from names.
- Deps don't receive the invoked task's CLI opts (only explicit edge :opts).
- Completions later: tasks + bb globals.

## Next implementation steps (rework of branch cli-tree-tasks)

1. Nested :tasks: flatten at bb-edn load; path addressing for run/depends;
   key-order walks nested maps.
2. Replace wrap-cli's :cmd dispatch with: resolve path -> parse remaining
   argv with leaf spec -> bind :opts (+ defaults) -> let-bind option names
   -> run leaf (with its :depends).
3. (run 'task {:opts ...}) + :depends [{:task t :opts m}].
4. Help pre-pass from bb.edn data (pure).
5. bb tasks listing for nested tasks (rendering TBD - was Q5, undecided).
6. Then: completions, book docs, ductile bb2.edn rewrite v2.
