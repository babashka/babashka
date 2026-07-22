# bb tasks CLI example

Demonstrates the task-runner CLI integration on the `cli-tree-tasks` branch:
options, auto-generated help, subcommands and shell completion for bb tasks,
driven by [babashka.cli](https://github.com/babashka/cli).

Run everything below from this directory.

## The example

Two files. `bb.edn`:

```clojure
{:paths ["src"]
 :tasks
 {-setup {:task (println "Running setup (a dependency task)")}

  ;; options declared inline; parsed opts via (:opts (current-task))
  clean {:doc "Removes build artifacts"
         :cli {:spec {:dry-run {:coerce :boolean :desc "Only print what would be removed"}}}
         :task (let [{:keys [dry-run]} (:opts (current-task))]
                 (println (if dry-run "Would remove target/" "Removing target/")))}

  ;; spec and doc live on the function's metadata (see src/tasks.clj)
  dev {:depends [-setup]
       :cli {:exec-fn tasks/dev}}

  ;; subcommands: bb deploy lock / bb deploy unlock; root :task runs on bare bb deploy
  deploy {:doc "Deploys the app"
          :cli {:cmd {lock   {:exec-fn tasks/lock}
                      unlock {:exec-fn tasks/unlock}}}
          :task (println "Deploying!")}}}
```

`src/tasks.clj`:

```clojure
(ns tasks)

(def environments #{"dev" "staging" "prod"})

(defn dev
  "Starts the dev system"
  {:org.babashka/cli
   {:spec {:port {:coerce :int :default 8080 :desc "HTTP port"}
           :sandbox {:coerce :boolean :alias :s :desc "Run sandboxed"}}}}
  [{:keys [port sandbox]}]
  (println "Starting dev system on port" port (if sandbox "(sandboxed)" "(unrestricted)")))

(defn lock
  "Locks deployments"
  {:org.babashka/cli
   {:spec {:environment {:desc "Target environment"
                         :validate environments
                         :require true
                         :positional true}
           :message {:alias :m :desc "Lock message" :require true}}
    :args->opts [:environment]}}
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

Three styles:

- `clean`: options declared inline under `:cli {:spec ...}`, parsed opts via
  `(:opts (current-task))`
- `dev`: `:cli {:exec-fn tasks/dev}`, the function receives the parsed options;
  spec comes from its `:org.babashka/cli` metadata and the docstring becomes
  the task doc
- `deploy`: a command tree under `:cli {:cmd ...}`; each leaf is an `:exec-fn`
  with its spec and doc on the function; the root `:task` runs on bare
  `bb deploy`

Notes:

- `:depends` runs before the task body but is skipped on `--help` and on
  parse errors (see the `bb dev --help` vs `bb dev` outputs below). Parsed
  options do not flow into dependency tasks.
- An option marked `:positional true` is listed under `Arguments:` and cannot
  be passed as a flag.
- A set-valued `:validate` validates, renders in the error message and doubles
  as completion candidates.
- Tasks without `:cli` are untouched: `*command-line-args*` as always.

## Session

```
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

$ bb clean --dry-run
Would remove target/

$ bb deploy --help
Usage: bb deploy [options] <command>

Commands:
  lock   Locks deployments
  unlock Unlocks deployments

Options:
  -h, --help  Show this help

Run "bb deploy <command> --help" for more information on a command.

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
```

## Completion

```
source <(bb org.babashka.cli/completions snippet --shell zsh)
```

Also bash, fish, PowerShell and nushell (`--shell bash`, ...).

```
$ bb <TAB>                # task names with docs, plus files
$ bb --<TAB>              # bb's own options
$ bb deploy <TAB>         # lock unlock
$ bb deploy unlock <TAB>  # dev prod staging
$ bb dev -<TAB>           # -s --sandbox --port ...
```

Convention (as in git and friends): a fresh word completes subcommands and
positional values; option names complete on a dash-prefixed word, or when
options are all a task accepts.

## Trying this with a dev build

Linux binaries are built per commit on CI: log in to CircleCI with GitHub
(free), open the
[branch pipeline](https://app.circleci.com/pipelines/github/babashka/babashka?branch=cli-tree-tasks),
pick the newest run, job `linux-static` (or `linux-aarch64-static`),
Artifacts tab, download `babashka-*-static.tar.gz`.
