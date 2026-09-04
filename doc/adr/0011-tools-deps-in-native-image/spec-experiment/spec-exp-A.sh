#!/usr/bin/env bash
# Build A: real clojure.tools.deps.specs, so clojure.spec is reachable.
set -eo pipefail
W=/Users/borkdude/dev/babashka/.claude/worktrees/spec-exp
rm -f "$W/feature-tools-deps/clojure/tools/deps/specs.clj"
rm -f "$W/feature-tools-deps/clojure/spec/gen/alpha.clj"
bash /Users/borkdude/dev/babashka/.claude/worktrees/tools-deps-native/poc-logs/spec-exp-build.sh A
