# bb tasks CLI example

Demonstrates CLI integration for bb tasks: options, auto-generated help,
subcommands and shell completion, driven by
[babashka.cli](https://github.com/babashka/cli).

Run everything below from this directory.

## The example

Two files. `bb.edn`:

```clojure
{:paths ["src"]
 :tasks
 {;; Defaults for every :cli task below. Function metadata and :cli keys win.
  :cli {:restrict true :restrict-args true}

  -setup {:task (println "Running setup (a dependency task)")}

  ;; The body reads options declared here with (:opts (current-task)).
  clean {:doc "Removes build artifacts"
         :cli {:spec {:dry-run {:coerce :boolean :desc "Only print what would be removed"}}}
         :task (let [{:keys [dry-run]} (:opts (current-task))]
                 (println (if dry-run "Would remove target/" "Removing target/")))}

  ;; The spec and doc live on the function's metadata in src/tasks.clj.
  dev {:depends [-setup]
       :cli {:exec-fn tasks/dev}}

  ;; Subcommands dispatch to functions and the root :task runs on bare bb deploy.
  deploy {:doc "Deploys the app"
          :cli {:cmd {lock   {:exec-fn tasks/lock}
                      unlock {:exec-fn tasks/unlock}}
                :epilog "Deployments are locked during maintenance windows."}
          :task (println "Deploying!")}}}
```

`src/tasks.clj`:

```clojure
(ns tasks)

(def environments #{"dev" "staging" "prod"})

(defn red-error
  "Prints the error in red and exits, replacing the default error output."
  [{:keys [msg]}]
  (binding [*out* *err*]
    (println (str "\u001b[31mError: " msg "\u001b[0m")))
  (System/exit 1))

;; Shared cli defaults for every fn in this ns: fn attr-maps are evaluated, so
;; each fn merges this in. bb.edn cannot hold an :error-fn (it is data).
(def cli-base {:error-fn red-error})

(defn dev
  "Starts the dev system"
  {:org.babashka/cli
   (merge cli-base
          {:spec {:port {:coerce :int :default 8080 :desc "HTTP port"}
                  :sandbox {:coerce :boolean :alias :s :desc "Run sandboxed"}}})}
  [{:keys [port sandbox]}]
  (println "Starting dev system on port" port (if sandbox "(sandboxed)" "(unrestricted)")))

(defn lock
  "Locks deployments"
  {:org.babashka/cli
   (merge cli-base
          {:spec {:environment {:desc "Target environment"
                                :validate environments
                                :require true
                                :positional true}
                  :message {:alias :m :desc "Lock message" :require true}}
           :args->opts [:environment]})}
  [{:keys [environment message]}]
  (println "Locking" environment "-" message))

(defn unlock
  "Unlocks deployments"
  {:org.babashka/cli
   {:spec {:environment {:desc "Target environment"
                         :validate environments
                         :require true
                         :positional true}}
    :args->opts [:environment]}}
  [{:keys [environment]}]
  (println "Unlocking" environment))
```

The example covers:

- `clean` declares its options inline and reads them with
  `(:opts (current-task))`.
- `dev` routes to a function with `:exec-fn`. The spec comes from the
  function's `:org.babashka/cli` metadata and the docstring becomes the task
  doc.
- `deploy` is a command tree. Each leaf is an `:exec-fn` with its spec and doc
  on the function. The root `:task` runs on bare `bb deploy`. When a task has
  both a `:task` body and a root `:exec-fn` in `:cli`, the `:exec-fn` takes
  priority.
- The `:cli` entry at the top of `:tasks` sets dispatch defaults for every
  `:cli` task. Here it rejects unknown options and stray positional arguments
  everywhere. Namespace metadata works too:
  `(ns tasks {:org.babashka/cli {:restrict true}})`, like `bb -x`.
- A `:cmd` tree may also live in a function's metadata instead of bb.edn.
  Function metadata is evaluated, so refer to subcommand functions with var
  literals: `{:cmd {"lock" {:exec-fn #'tasks/lock}}}`. A var contributes its
  function's spec and docstring. Quoted symbols work too; a bare function
  value routes but carries no metadata.

Notes:

- `:depends` runs before the task body but is skipped on `--help` and on
  parse errors. Parsed options do not flow into dependency tasks.
- An option marked `:positional true` is listed under `Arguments:` and cannot
  be passed as a flag.
- A set-valued `:validate` supplies validation choices, error text, and
  completion candidates.
- Tasks without `:cli` still receive arguments through `*command-line-args*`.

## Session

```console
$ bb tasks
The following tasks are available:

clean  Removes build artifacts
dev    Starts the dev system
deploy Deploys the app

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

$ bb dev --nope
Error: Unknown option: --nope

$ bb clean --dry-run
Would remove target/

$ bb deploy --help
Usage: bb deploy [options] <command>

Deploys the app

Commands:
  lock   Locks deployments
  unlock Unlocks deployments

Options:
  -h, --help  Show this help

Run "bb deploy <command> --help" for more information on a command.

Deployments are locked during maintenance windows.

$ bb deploy
Deploying!

$ bb deploy lock --help
Usage: bb deploy lock [options] <environment>

Locks deployments

Arguments:
  <environment>  Target environment

Options:
  -m, --message  Lock message (required)
  -h, --help     Show this help

$ bb deploy lock prod -m "release 42"
Locking prod - release 42

$ bb deploy lock qa -m x
Error: Invalid value for argument <environment>: qa. Expected one of: dev, prod, staging

$ bb deploy lock prod extra -m x
Error: Unexpected argument: extra
```

## Custom error output

`red-error` in `src/tasks.clj` replaces the default error output with an
ANSI-red line. An `:error-fn` goes in function metadata; define it once and
merge it into each function's cli map (`cli-base` above). bb.edn cannot hold
an `:error-fn`: bb.edn is data and bb rejects the key there with an error.

```console
$ bb dev --nope
Error: Unknown option: --nope        <- red

$ bb deploy lock qa -m x
Error: Invalid value for argument <environment>: qa. Expected one of: dev, prod, staging        <- red
```

Coverage follows the functions: errors at a level whose function carries the
metadata use the handler. The `deploy` root is a plain `:task` body, so
root-level errors such as an unknown command keep the default output.

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

```console
$ bb lock bogus-env -m hi
Error: Invalid value for option --environment: bogus-env. Expected one of: dev, prod, staging

Usage: bb lock [options] <environment>

Locks deployments

Options:
      --environment  Target environment (required)
  -m, --message      Lock message (required)
  -h, --help         Show this help
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

`bb -x` addresses a single var and does not dispatch a `:cmd` tree: extra
words after the options are dropped unless the function's metadata sets
`:restrict-args`.

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
$ bb deploy unlock <TAB>  # dev prod staging
$ bb dev -<TAB>           # -s --sandbox --port ...
```

Completion after a space offers subcommands and positional values.
Completion after a dash offers option names. A task that only accepts options
offers them after a space as well.

## FAQ

**Can I still run these functions outside the task runner?**
Yes. Every function is invocable with `bb -x tasks/lock prod -m msg`, and a
script with its own `-main` calling `babashka.cli/dispatch` works as before.

**Two commands share the same options. Do I copy the spec?**
No. Spec entries are data:

```clojure
(def env-opt {:desc "Environment" :validate #{"dev" "prod"} :require true :positional true})

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
db {:cli {:cmd {up   {:exec-fn tasks/migrate}
                down {:exec-fn tasks/migrate}}}}
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
`:exec-fn` calls the function with the parsed options map. `:fn` calls it
with the whole dispatch result:

```clojure
(defn a {:org.babashka/cli {:spec {:env {}}}}
  [{:keys [env]}] ...)        ;; :exec-fn shape: opts directly

(defn b {:org.babashka/cli {:spec {:env {}}}}
  [{:keys [opts dispatch]}] ...)  ;; :fn shape: {:opts ... :dispatch ... :args ...}
```

Mixing them up does not error, the function destructures nils:

```console
$ bb mixed --env x     # :fn-shaped function wired as :exec-fn
opts: nil
```

When in doubt use `:exec-fn`.

**Doesn't this make bb.edn noisy?**
Keep bb.edn to routing: `dev {:exec-fn tasks/dev}` is the whole entry.
Specs, docs and error handling live on the functions.

**Where does the task description come from?**
Task `:doc` first, then the exec-fn's docstring. Both `bb tasks` and `--help`
use the same fallback:

```clojure
{:tasks {t1 {:doc "Task :doc, fn has none"
             :cli {:exec-fn df/nodoc}}
         t2 {:cli {:exec-fn df/withdoc}}
         t3 {:doc "Task :doc present"
             :cli {:exec-fn df/withdoc}}}}   ;; withdoc: "Docstring from the fn"
```

```console
$ bb tasks
The following tasks are available:

t1 Task :doc, fn has none
t2 Docstring from the fn
t3 Task :doc present
```

**Commands render in map order. Is that reliable?**
Yes. Clojure loses map order beyond 8 entries, but bb recovers the written
order from the bb.edn text or the function's source. When the metadata map is
computed (e.g. built with `merge`) there is no literal source order: add
`:cmd-order` or use the vector form `:cmd [["lock" {...}] ["unlock" {...}]]`.

## Trying this with a dev build

Dev builds of this branch:

- [babashka-linux-amd64-static.tar.gz](https://output.circle-artifacts.com/output/job/72c1253f-a145-46e5-aeff-922617cc7bc6/artifacts/0/release/babashka-1.12.219-SNAPSHOT-linux-amd64-static.tar.gz)
- [babashka-macos-amd64.tar.gz](https://output.circle-artifacts.com/output/job/4d5966c1-09be-4fec-9234-5532e6822b88/artifacts/0/release/babashka-1.12.219-SNAPSHOT-macos-amd64.tar.gz)
  (Intel, runs on Apple Silicon via Rosetta)
- [babashka-windows-amd64.zip](https://nightly.link/babashka/babashka/actions/runs/30008686278/babashka-1.12.219-SNAPSHOT-windows-amd64.zip)

For other architectures or newer commits:

1. Open the [branch pipeline](https://app.circleci.com/pipelines/github/babashka/babashka?branch=cli-tree-tasks) and log in if prompted.
2. Pick the newest run and the `linux-static` or `linux-aarch64-static` job.
3. Download `babashka-*-static.tar.gz` from the Artifacts tab.
