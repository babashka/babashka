### Deps.clj

The [`deps.clj`](https://github.com/borkdude/deps.clj/) script can be used to work with `deps.edn`-based projects:

``` shell
$ deps.clj -A:my-script -Scommand "bb -cp {{classpath}} {{main-opts}}"
Hello from gist script!
```

Create these aliases for brevity:

``` shell
$ alias bbk='deps.clj -Scommand "bb -cp {{classpath}} {{main-opts}}"'
$ alias babashka='rlwrap deps.clj -Scommand "bb -cp {{classpath}} {{main-opts}}"'
$ bbk -A:my-script
Hello from gist script!
$ babashka
Babashka v0.0.58 REPL.
Use :repl/quit or :repl/exit to quit the REPL.
Clojure rocks, Bash reaches.

user=> (require '[my-gist-script :as mgs])
nil
user=> (mgs/-main)
Hello from gist script!
nil
```

You can also use for example `deps.clj` to produce the classpath for a
`babashka` REPL:

```shellsession
$ cat script/start-repl.sh
#!/bin/sh -e
git_root=$(git rev-parse --show-toplevel)
export BABASHKA_CLASSPATH=$("$git_root"/script/deps.clj -Spath)
bb --socket-repl 1666
$ ./script/start-repl.sh
Babashka socket REPL started at localhost:1666
```

Now, given that your `deps.edn` and source tree looks something like

```shellsession
$ cat deps.edn
{:paths ["src" "test"]
 :deps  {}}
$ tree -L 3
├── deps.edn
├── README
├── script
│   ├── deps.clj
│   └── start-repl.sh
├── src
│   └── project_namespace
│       ├── main.clj
│       └── utilities.clj
└── test
    └── project_namespace
        ├── test_main.clj
        └── test_utilities.clj

```

you should now be able to `(require '[multi-machine-rsync.utilities :as util])`
in your REPL and the source code in `/src/multi_machine_rsync/utilities.clj`
will be evaluated and made available through the symbol `util`.
