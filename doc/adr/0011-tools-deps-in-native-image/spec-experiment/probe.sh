#!/usr/bin/env bash
# Run the experiment binary's resolve probe for a few symbols.
B=/Users/borkdude/dev/babashka/.claude/worktrees/spec-exp/bb
for s in 'clojure.core/*ns*' 'clojure.core/*print-length*' 'clojure.core/inc' 'clojure.core/print-method' 'babashka.main/main' 'clojure.core/*data-readers*' 'nope/nope' \
         'clojure.core/unchecked-add-int' 'clojure.string/blank?' 'taoensso.timbre/info' 'clojure.core.async/go' 'clojure.tools.deps/resolve-deps' 'clojure.spec.alpha/valid?'; do
  printf '%-34s ' "$s"
  BB_RESOLVE_PROBE="$s" "$B" -e nil 2>&1 | head -2 | tr '\n' ' '
  echo
done
echo "sci resolve in a script:"
"$B" -e '(prn (resolve (quote inc)) (some? (find-ns (quote clojure.string))))'
