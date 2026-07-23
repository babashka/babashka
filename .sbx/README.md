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
