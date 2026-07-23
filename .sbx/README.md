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
