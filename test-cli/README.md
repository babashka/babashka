# bb tasks CLI example

Demonstrates CLI integration for bb tasks: options, auto-generated help,
subcommands and shell completion, driven by
[babashka.cli](https://github.com/babashka/cli).

Run everything below from this directory.

## The example

Two files. `bb.edn` is a thin registry: task names, dependencies and command
trees. `bb.edn`:

```clojure
{:paths ["src"]
 :tasks
 {;; Dispatch defaults for every CLI task: a symbol naming a def, so the map
  ;; may contain functions (here an :error-fn).
  :cli tasks/base-opts

  -setup {:task (println "Running setup (a dependency task)")}

  ;; A flag needs a function: clean's --dry-run lives on tasks/clean's metadata.
  clean {:exec-fn tasks/clean}

  ;; Spec and doc live on the function's metadata in src/tasks.clj.
  dev {:depends [-setup] :exec-fn tasks/dev}

  ;; A command group. Bare `bb deploy` has no handler, so it prints
  ;; "No command given"; the subcommands dispatch to functions.
  deploy {:doc "Manage deployments"
          :cmd {"lock"   {:exec-fn tasks/lock}
                "unlock" {:exec-fn tasks/unlock}}}}}
```

All the CLI richness (specs, docs, `:enum`, `:error-fn`, dispatch defaults)
lives on the functions in `src/tasks.clj`, where the map is evaluated and
functions just work:

```clojure
(ns tasks
  ;; Parsing defaults for every function in this namespace, like `bb -x`.
  {:org.babashka/cli {:restrict true :restrict-args true}}
  (:require [babashka.cli :as cli]))

(def environments ["dev" "staging" "prod"])

(defn red-error
  "Prints the standard error message in red and exits, replacing the default
  error output."
  [data]
  (binding [*out* *err*]
    (println (str "\u001b[31m" (cli/format-command-error data) "\u001b[0m")))
  (System/exit 1))

;; Dispatch defaults for every CLI task, referenced from bb.edn as
;; :tasks {:cli tasks/base-opts}. A def may hold functions; bb.edn cannot.
(def base-opts {:error-fn red-error})

(defn clean
  "Removes build artifacts"
  {:org.babashka/cli {:spec {:dry-run {:coerce :boolean :desc "Only print what would be removed"}}}}
  [{:keys [dry-run]}]
  (println (if dry-run "Would remove target/" "Removing target/")))

(defn dev
  "Starts the dev system"
  {:org.babashka/cli {:spec {:port {:coerce :int :default 8080 :desc "HTTP port"}
                             :sandbox {:coerce :boolean :alias :s :desc "Run sandboxed"}}}}
  [{:keys [port sandbox]}]
  (println "Starting dev system on port" port (if sandbox "(sandboxed)" "(unrestricted)")))

(defn lock
  "Locks deployments"
  {:org.babashka/cli {:spec {:environment {:desc "Target environment"
                                           :enum environments
                                           :require true
                                           :positional true}
                             :message {:alias :m :desc "Lock message" :require true}}
                      :args->opts [:environment]}}
  [{:keys [environment message]}]
  (println "Locking" environment "-" message))

(defn unlock
  "Unlocks deployments"
  {:org.babashka/cli {:spec {:environment {:desc "Target environment"
                                           :enum environments
                                           :require true
                                           :positional true}}
                      :args->opts [:environment]}}
  [{:keys [environment]}]
  (println "Unlocking" environment))
```

The example covers the two shapes a CLI task takes:

- **A function**, wired with `:exec-fn`. `clean` and `dev` point at a function;
  its `:org.babashka/cli` metadata is the spec, its docstring is the task doc.
- **A command group**, wired with `:cmd`. `deploy` has no handler, so it is a
  pure router: bare `bb deploy` prints `No command given.`, and each leaf
  points at a function. Add an `:exec-fn` next to `:cmd` (or a `:task` body) if
  you want a default action on the bare command.

Everything else is data or existing bb: `:depends`, plain `:task` bodies, task
`:doc`. Parsing defaults (`:restrict`, `:restrict-args`) sit on the namespace
metadata and apply to every function in it, like for `bb -x`. Dispatch defaults
that include functions, such as the `:error-fn`, live in the `base-opts` def
that bb.edn's `:cli` entry names; they apply to every CLI task, including
command groups, which have no function of their own.

Notes:

- `:depends` runs before the task body but is skipped on `--help` and on parse
  errors. Parsed options do not flow into dependency tasks.
- `:positional true` and `:args->opts` arguments are listed under `Arguments:`
  as `<name>`, not in `Options:` as `--name`.
- `:enum` lists allowed values in order: it derives validation and supplies the
  help choices, error text, and completion candidates.
- An option shared by a parent command is only accepted after the subcommand
  when it is marked `:inherit true`; a required `:inherit` option may be given
  on either side.
- Tasks without `:exec-fn`/`:cmd` receive raw arguments through
  `*command-line-args*`, unchanged.

## Session

```console
$ bb tasks
The following tasks are available:

clean  Removes build artifacts
dev    Starts the dev system
deploy Manage deployments

$ bb dev --help
Usage: bb dev [options]

Starts the dev system

Options:
      --port     HTTP port (default: 8080)
  -s, --sandbox  Run sandboxed
  -h, --help     Show this help

$ bb dev
Running setup (a dependency task)
Starting dev system on port 8080 (unrestricted)

$ bb dev -s --port 3000
Running setup (a dependency task)
Starting dev system on port 3000 (sandboxed)

$ bb clean --dry-run
Would remove target/

$ bb deploy
No command given.        <- red, via the :cli defaults (see below)

Commands:
  lock   Locks deployments
  unlock Unlocks deployments

Run "bb deploy --help" for more information.

$ bb deploy lock --help
Usage: bb deploy lock [options] <environment>

Locks deployments

Arguments:
  <environment>  Target environment (one of: dev, staging, prod)

Options:
  -m, --message  Lock message (required)
  -h, --help     Show this help

$ bb deploy lock prod -m "release 42"
Locking prod - release 42

$ bb deploy unlock prod
Unlocking prod
```

## Custom error output

`red-error` in `src/tasks.clj` prints the standard message (rendered by
`babashka.cli/format-command-error`) in red and exits. An `:error-fn` is a
function, so it cannot sit in bb.edn, which is data. Instead the `base-opts`
def holds it, and bb.edn's `:cli tasks/base-opts` entry names that def: the
symbol resolves to the var, so the map may contain functions. These dispatch
defaults apply to every CLI task, including the `deploy` group, which has no
function of its own to carry a handler:

```console
$ bb dev --nope
Error: Unknown option: --nope        <- red, all of it

Usage: bb dev [options]

Run "bb dev --help" for more information.

$ bb deploy bogus
Unknown command: bogus        <- red: a group error, unreachable from any function's metadata

Commands:
  lock   Locks deployments
  unlock Unlocks deployments

Run "bb deploy --help" for more information.

$ bb deploy lock qa -m x
Error: Invalid value for argument <environment>: qa. Expected one of: dev, staging, prod        <- red

Usage: bb deploy lock [options] <environment>

Run "bb deploy lock --help" for more information.
```

A function's own `:error-fn` (in its `:org.babashka/cli` metadata) wins over
the defaults for that function's errors.

An `:error-fn` can also print the full usage help after the error, instead of
the default one-line tip. The handler receives `:tree`, `:dispatch` and
`:prog`, the inputs of `babashka.cli/format-command-help`, the renderer
`--help` uses:

```clojure
(defn verbose-error
  "On usage error, print the message and the full usage help."
  [{:keys [msg tree dispatch prog]}]
  (binding [*out* *err*]
    (println (str "Error: " msg))
    (println)
    (println (cli/format-command-help {:table tree :cmds dispatch :prog prog})))
  (System/exit 1))
```

`:dispatch` is the command path, so the help is the failing command's own, not
the root's.

## bb -x

The same functions are directly invocable with `bb -x`. The same metadata
drives parsing:

```console
$ bb -x tasks/lock prod -m "release 42"
Locking prod - release 42

$ bb -x tasks/dev --port 3000
Starting dev system on port 3000 (unrestricted)
```

`bb -x` addresses a single var and does not dispatch a `:cmd` tree: extra words
after the options are dropped unless the function's metadata sets
`:restrict-args` (as this namespace's does).

## Completion

To install completions, add this to your zsh init file after `compinit`:

```shell
source <(bb org.babashka.cli/completions snippet --shell zsh)
```

The same line with `--shell bash` goes in your bash init file. Fish uses
`bb org.babashka.cli/completions snippet --shell fish | source` in
`config.fish`. PowerShell pipes the snippet through
`Out-String | Invoke-Expression` in `$PROFILE`. Nushell saves the snippet to a
file and sources it from `config.nu`. See
[Completions](https://github.com/babashka/cli#completions) in the babashka.cli
README for per-shell details.

```console
$ bb <TAB>                # task names with docs, plus files
$ bb --<TAB>              # bb's own options
$ bb deploy <TAB>         # lock unlock
$ bb deploy lock <TAB>    # dev staging prod
$ bb dev -<TAB>           # -s --sandbox --port ...
```

Completion after a space offers subcommands and positional values. Completion
after a dash offers option names.

Completion loads a task's namespace to read the spec and the docstring, so a
task with `:extra-deps` that are not in the local cache can make the first TAB
pause while they resolve. Give such a task an explicit `:doc` to keep it out of
task-name completion.

## FAQ

**Can I still run these functions outside the task runner?**
Yes. Every function is invocable with `bb -x tasks/lock prod -m msg`, and a
script with its own `-main` calling `babashka.cli/dispatch` works as before.

**Two commands share the same options. Do I copy the spec?**
No. Spec entries are data:

```clojure
(def env-opt {:desc "Environment" :enum ["dev" "prod"] :require true :positional true})

(defn migrate
  {:org.babashka/cli {:spec {:env env-opt} :args->opts [:env]}}
  [{:keys [env]}] ...)

(defn rollback
  {:org.babashka/cli {:spec {:env env-opt} :args->opts [:env]}}
  [{:keys [env]}] ...)
```

Options shared by every function in a namespace go on the namespace metadata.

**One function serves two commands. Which one was invoked?**

```clojure
;; bb.edn
db {:cmd {"up"   {:exec-fn tasks/migrate}
          "down" {:exec-fn tasks/migrate}}}
```

```clojure
(defn migrate [opts]
  (let [[cmd] (-> opts meta :org.babashka/cli :dispatch)]
    (println "Migrating" cmd)))
```

```console
$ bb db up
Migrating up
$ bb db down
Migrating down
```

A `:fn` function receives the same information as `:dispatch` in its argument
map.

**`:fn` or `:exec-fn`?**
`:exec-fn` calls the function with the parsed options map. `:fn` calls it with
the whole dispatch result:

```clojure
(defn a {:org.babashka/cli {:spec {:env {}}}}
  [{:keys [env]}] ...)             ;; :exec-fn shape: opts directly

(defn b {:org.babashka/cli {:spec {:env {}}}}
  [{:keys [opts dispatch]}] ...)   ;; :fn shape: {:opts ... :dispatch ... :args ...}
```

When in doubt use `:exec-fn`.

**Where does the task description come from?**
Task `:doc` first, then the exec-fn's docstring. Both `bb tasks` and `--help`
use the same fallback:

```clojure
{:tasks {t1 {:doc "Task :doc, fn has none" :exec-fn df/nodoc}
         t2 {:exec-fn df/withdoc}                              ;; "Docstring from the fn"
         t3 {:doc "Task :doc present" :exec-fn df/withdoc}}}
```

```console
$ bb tasks
The following tasks are available:

t1 Task :doc, fn has none
t2 Docstring from the fn
t3 Task :doc present
```

**How do I write a longer description in bb.edn?**
`:doc` accepts a vector of lines, joined with newlines. edn has no `str/join`,
and a multi-line string forces continuation lines to column 0:

```clojure
{:tasks {migrate {:doc ["Migrates the database."
                        ""
                        "Runs pending migrations in order."]
                  :exec-fn tasks/migrate}}}
```

```console
$ bb migrate --help
Usage: bb migrate [options]

Migrates the database.

Runs pending migrations in order.
...
```

`:doc` and `:epilog` on `:cmd` nodes take vectors too.

**Commands render in map order. Is that reliable?**
Yes. Clojure loses map order beyond 8 entries, but bb recovers the written
order from the bb.edn text. When the command tree is a computed map there is no
literal source order: add `:cmd-order` or use the vector form
`:cmd [["lock" {...}] ["unlock" {...}]]`.

## Trying this with a dev build

Dev builds of this branch:

- [babashka-linux-amd64-static.tar.gz](https://output.circle-artifacts.com/output/job/3e1405db-bfac-4444-903c-1185d4816a02/artifacts/0/release/babashka-1.12.219-SNAPSHOT-linux-amd64-static.tar.gz)
- [babashka-macos-amd64.tar.gz](https://output.circle-artifacts.com/output/job/f8e351f5-cc43-45ca-8371-0417da5c2087/artifacts/0/release/babashka-1.12.219-SNAPSHOT-macos-amd64.tar.gz)
  (Intel, runs on Apple Silicon via Rosetta)
- [babashka-windows-amd64.zip](https://nightly.link/babashka/babashka/actions/runs/30108060616/babashka-1.12.219-SNAPSHOT-windows-amd64.zip)

For other architectures or newer commits:

1. Open the [branch pipeline](https://app.circleci.com/pipelines/github/babashka/babashka?branch=cli-tree-tasks) and log in if prompted.
2. Pick the newest run and the `linux-static` or `linux-aarch64-static` job.
3. Download `babashka-*-static.tar.gz` from the Artifacts tab.
