# Sandbox REPL

Starts an nREPL inside the project sandbox. One sandbox serves the main
checkout and all worktrees.

Start a REPL for a worktree:

``` shell
bb .sbx/repl.clj --root /path/to/worktree
```

The script is idempotent and prints the port on its last line. It reuses a
running REPL for the same root. The port is also written to `.nrepl-port` in
the root.

Evaluate against it:

``` shell
clj-nrepl-eval --port <port> "(+ 1 1)"
```

Restart after the REPL dies or to pick up new deps:

``` shell
sbx exec babashka-repl -- pkill -f nrepl.cmdline
bb .sbx/repl.clj --root /path/to/worktree
```

The REPL runs Clojure 1.12 via an injected `:repl-clojure` alias, so
`clojure.repl.deps/add-libs` works for adding deps on the fly.

[clj-reload](https://github.com/tonsky/clj-reload) is included for reloading
changed namespaces in dependency order:

``` clojure
(require '[clj-reload.core :as reload])
(reload/init {:dirs ["src" "test"]})
(reload/reload)
```

Init on `src` and `test` only. `test-resources` holds the lib-test corpus, and
`{:only :all}` fails on feature-gated namespaces that are not on the test
classpath.

## Reloading babashka.impl.* does not work

Restart the REPL after changing anything under `src/babashka/impl`. Those
namespaces build the sci environment at load time, with `sci/copy-var` and
friends. Reloading one creates fresh sci vars while the live context still
holds the old ones, and the mismatch shows up as wrong behavior rather than an
error: after a `load-file` of `impl/tasks.clj`, `(current-task)` returns nil
throughout the test suite, failing tests that have nothing to do with the
change. `clj-reload` hits the same wall from the other side, cascading into
dependents such as `babashka.impl.pprint`, which throws on reload.

So clj-reload is for test namespaces. For source changes, restart:

``` shell
sbx exec babashka-repl -- pkill -f nrepl.cmdline
bb .sbx/repl.clj --root /path/to/checkout
```

Start one restart at a time. Two overlapping restarts race over `.nrepl-port`,
and the REPL that wins can come up without the `:test` alias, which looks like
test namespaces vanishing: `(require 'babashka.bb-edn-test)` reports "Could not
locate ... on classpath" while the file is plainly there. Check with
`(clojure.java.io/resource "babashka/bb_edn_test.clj")` and restart again if it
is nil.

The REPL runs inside the container: it only sees files under the mounted
project root, not host temp dirs. See `repl.clj` for `--port` and `--aliases`.

## The REPL must run detached: sbx exec -d

Since sbx v0.35 a container stops as soon as its last attached exec session
ends. A REPL started with `setsid ... &` inside a normal `sbx exec` dies with
the container moments after the starter exits, and `connect` then fails with
connection refused on a port that `sbx ports` still lists as published.
`start!` therefore launches the REPL with `sbx exec -d` (detached mode), which
keeps the process and the container alive with no session attached.

A container created under an older sbx can keep running with the old setsid
approach until it is recreated, which hides the problem on one machine and
shows it on the next. When connect fails: rerun `bb .sbx/repl.clj`, it prunes
dead port publishes and starts a fresh REPL, then read the new port from
`.nrepl-port`.

This kit came from clj-kondo's `.sbx`. The dead-port pruning in `repl.clj`
was that repo's fix (clj-kondo PR 2902); the `-d` fix originated here and
belongs in any copy of this kit.
