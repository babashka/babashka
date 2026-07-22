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
 {-setup {:task (println "Running setup (a dependency task)")}

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

The example covers:

- `clean` declares its options inline and reads them with
  `(:opts (current-task))`.
- `dev` routes to a function with `:exec-fn`. The spec comes from the
  function's `:org.babashka/cli` metadata and the docstring becomes the task
  doc.
- `deploy` is a command tree. Each leaf is an `:exec-fn` with its spec and doc
  on the function. The root `:task` runs on bare `bb deploy`.

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

## Trying this with a dev build

Dev builds of this branch:

- [babashka-linux-amd64-static.tar.gz](https://output.circle-artifacts.com/output/job/af771031-ca27-4ba1-9d54-cccdac752156/artifacts/0/release/babashka-1.12.219-SNAPSHOT-linux-amd64-static.tar.gz)
- [babashka-macos-amd64.tar.gz](https://output.circle-artifacts.com/output/job/54aa9355-039c-408a-a33f-414b621f4b2d/artifacts/0/release/babashka-1.12.219-SNAPSHOT-macos-amd64.tar.gz)
  (Intel, runs on Apple Silicon via Rosetta)
- [babashka-windows-amd64.zip](https://nightly.link/babashka/babashka/actions/runs/29949523082/babashka-1.12.219-SNAPSHOT-windows-amd64.zip)

For other architectures or newer commits:

1. Open the [branch pipeline](https://app.circleci.com/pipelines/github/babashka/babashka?branch=cli-tree-tasks) and log in if prompted.
2. Pick the newest run and the `linux-static` or `linux-aarch64-static` job.
3. Download `babashka-*-static.tar.gz` from the Artifacts tab.
