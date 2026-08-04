# Task changes

A task with `:exec-fn` or `:cmd` is a CLI task. Before, a CLI task did nothing
when another task named it in `:depends`. It ran only when you invoked it
directly, and the run gave no error.

Now it runs, if the task that you invoke is a CLI task too. Its options join
the parse of that task, so they are accepted and `--help` shows them. The
handler receives the options that it declared, and no others.

A plain task does not run a CLI dependency. Only the task that parses the
options can call the handler of a dependency.

## Example

```clojure
;; bb.edn
{:paths ["bb"]
 :tasks {a {:exec-fn tasks/dude}
         b {:depends [a]
            :exec-fn tasks/foobar}}}
```

```clojure
(ns tasks)

(defn dude
  {:org.babashka/cli {:spec {:foo {:coerce :int :desc "foo from task a"}}}}
  [opts] (prn [:dude opts]))

(defn foobar
  {:org.babashka/cli {:spec {:bar {:coerce :int :desc "bar from task b"}}}}
  [opts] (prn [:foobar opts]))
```

```
$ bb b --foo 1 --bar 2
[:dude {:foo 1}]
[:foobar {:foo 1, :bar 2}]
```

`--foo` belongs to task `a`. Task `b` accepts it because `a` is a dependency.
Task `a` does not receive `--bar`.

```
$ bb b --help
Usage: bb b [options]

Options:
      --bar   bar from task b
  -h, --help  Show this help

Inherited options:
  --foo  foo from task a
```

## Notes

`bb run --parallel` runs the dependencies at the same time. CLI dependencies
run at the same time too.

One parse covers the whole invocation. A dependency never parses, so
`:restrict` on a dependency has no effect there.

This needs babashka.cli 0.12.86. That release shows the dispatch-level `:spec`
in help. The parser always accepted those options, but help did not show them.
